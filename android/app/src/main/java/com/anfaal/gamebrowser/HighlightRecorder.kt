package com.anfaal.gamebrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/**
 * Save state for the floating "instant replay" capture button. Mirrors
 * ContentView.swift's `BrowserViewModel.HighlightSaveState` (idle/saving/
 * saved/failed) — see `HighlightButton.kt` for the icon/colour mapping that
 * matches iOS's `highlightIcon`/`highlightColor`.
 */
enum class HighlightSaveState { Idle, Saving, Saved, Failed }

/**
 * Android port of `HighlightRecorder.swift`'s console-style "instant replay".
 *
 * ---------------------------------------------------------------------------
 * HONESTY NOTE — the UX gap vs iOS (please read before "improving" this):
 * ---------------------------------------------------------------------------
 * iOS's ReplayKit (`RPScreenRecorder.startClipBuffering`) keeps an invisible,
 * permission-dialog-free, indicator-free rolling ~15s buffer of on-screen
 * content the moment the setting is flipped on. Android has *no equivalent*.
 *
 * The only screen-capture primitive Android exposes is `MediaProjection`, and
 * by OS policy it cannot be made invisible:
 *  1. It requires the user to accept a **system consent dialog**
 *     (`MediaProjectionManager.createScreenCaptureIntent()` launched for a
 *     result from an Activity). This dialog cannot be skipped, pre-authorised,
 *     or triggered without a foreground user gesture, and — on modern Android —
 *     the granted token is good for a **single** capture session, so it must be
 *     re-requested every time buffering is (re)started.
 *  2. While capturing, it **must** run inside a foreground service of type
 *     `mediaProjection`, which forces a **persistent, user-visible, ongoing
 *     notification**, and Android 14+ additionally draws its own system screen-
 *     capture indicator. There is no supported way to buffer the screen
 *     silently in the background the way iOS does.
 *
 * So the honest Android shape of this feature is: a one-time system permission
 * prompt when the user enables it, plus a visible "recording" notification the
 * whole time the rolling buffer is live. This file does not pretend otherwise
 * (the same rigor `KeepAliveService.kt` applies to its own FGS-type honesty
 * note). Unlike that service, though, `mediaProjection` is one of Android's
 * concrete, named foreground-service types — screen capture is exactly what it
 * is for — so none of `specialUse`'s Play Store review risk applies here.
 *
 * ---------------------------------------------------------------------------
 * How the pieces fit together:
 * ---------------------------------------------------------------------------
 *  - This object is the coordination facade (the analogue of iOS's
 *    `HighlightRecorder.shared`). It owns the `wantsBuffering` vs `isBuffering`
 *    split — exactly as the Swift version does — so a capture session that
 *    finishes starting *after* the user has already turned the feature back off
 *    self-corrects (tears itself down) instead of buffering against their
 *    wishes.
 *  - The heavy lifting (MediaProjection -> VirtualDisplay -> MediaCodec H.264
 *    encoder -> in-memory ring buffer of encoded samples -> MediaMuxer ->
 *    MediaStore) lives in [HighlightCaptureService], the required foreground
 *    service.
 *  - Because getting a `MediaProjection` needs an Activity to host the consent
 *    dialog, the enable path can't run entirely inside the ViewModel. The
 *    ViewModel calls [enable]; [enable] fires [onRequestConsent] (which the
 *    Activity wires to its screen-capture ActivityResultLauncher); the Activity
 *    hands the granted token back via [onConsentGranted].
 */
object HighlightRecorder {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Roughly how much play we keep buffered (a bit over the 15s save window
     *  so a full 15s clip is always available, keyframe-aligned). */
    const val RETENTION_US: Long = 18_000_000L

    /** How much we actually export on save, matching iOS's `duration: 15`. */
    const val SAVE_US: Long = 15_000_000L

    /**
     * What the *setting* currently wants, independent of [isBuffering] (which
     * only reflects a confirmed, running capture session). Mirrors
     * `HighlightRecorder.swift`'s `wantsBuffering`.
     */
    @Volatile
    var wantsBuffering: Boolean = false
        private set

    /** True while a capture session is actually live. Observable so the UI /
     *  settings can reflect reality (e.g. after the OS revokes projection). */
    var isBuffering by mutableStateOf(false)
        private set

    /** The running service instance, set/cleared by the service itself. */
    private var service: HighlightCaptureService? = null

    // --- Activity-provided hooks (wired in MainActivity) -------------------

    /**
     * Invoked when buffering wants to start but has no live projection: the
     * Activity should launch `MediaProjectionManager.createScreenCaptureIntent()`
     * for a result. Null if no Activity is currently able to host the dialog
     * (in which case buffering simply won't start until the next enable).
     */
    var onRequestConsent: (() -> Unit)? = null

    /**
     * Invoked (on the main thread) when a live capture session ends for a
     * reason the *user* didn't ask for at the setting level — the OS revoked
     * projection (system "Stop" button), the consent dialog was declined, or an
     * encoder error tore it down. The Activity should reflect this by flipping
     * `viewModel.highlightsEnabled` back to false so Settings and the floating
     * button stop implying a buffer is running when it isn't.
     */
    var onBufferingUnavailable: (() -> Unit)? = null

    // --- Enable / disable (called from the ViewModel setter) ---------------

    /** User turned the setting on. Asks the Activity for capture consent
     *  unless a session is already live. */
    fun enable(@Suppress("UNUSED_PARAMETER") context: Context) {
        if (isBuffering) {
            wantsBuffering = true
            return
        }
        val request = onRequestConsent
        if (request == null) {
            // No Activity to host the system consent dialog right now. Can't
            // silently pre-authorise on Android, so surface that the feature
            // couldn't actually start rather than leaving the UI implying it did.
            // wantsBuffering stays false here (rather than being set and then
            // immediately relying on a round-trip through onBufferingUnavailable
            // to unset it) so internal state can't get stuck "wants" if that
            // callback is ever unwired (e.g. between onDestroy and a new Activity).
            notifyUnavailable()
            return
        }
        wantsBuffering = true
        request.invoke()
    }

    /** User turned the setting off (or we're force-correcting). Tears down any
     *  live session. Safe to call when nothing is running. */
    fun disable(context: Context) {
        wantsBuffering = false
        HighlightCaptureService.stop(context)
    }

    /** The Activity received a granted screen-capture result. */
    fun onConsentGranted(context: Context, resultCode: Int, data: Intent) {
        if (!wantsBuffering) {
            // The user disabled the feature while the consent dialog was up —
            // self-correct by not starting (mirrors the Swift wantsBuffering
            // check after startClipBuffering completes).
            return
        }
        HighlightCaptureService.start(context, resultCode, data)
    }

    /** The Activity's screen-capture consent was declined / cancelled. */
    fun onConsentDenied() {
        wantsBuffering = false
        notifyUnavailable()
    }

    // --- Called by the service --------------------------------------------

    internal fun onCaptureStarted(instance: HighlightCaptureService) {
        service = instance
        mainHandler.post { isBuffering = true }
        // If the user disabled the feature between granting consent and the
        // capture actually coming up, stop now instead of buffering against
        // their wishes (the Android analogue of the Swift self-correct).
        if (!wantsBuffering) {
            HighlightCaptureService.stop(instance.applicationContext)
        }
    }

    internal fun onCaptureStopped(instance: HighlightCaptureService, revoked: Boolean) {
        if (service === instance) service = null
        mainHandler.post { isBuffering = false }
        if (revoked && wantsBuffering) {
            // The OS pulled projection out from under us while the user still
            // wanted it on — there's nothing we can do without re-prompting, so
            // reflect that the buffer is no longer running.
            wantsBuffering = false
            notifyUnavailable()
        }
    }

    private fun notifyUnavailable() {
        val cb = onBufferingUnavailable ?: return
        mainHandler.post { cb.invoke() }
    }

    // --- Save (called from the ViewModel) ----------------------------------

    /**
     * Export the last ~15s of buffered play to the gallery (MediaStore.Video).
     * The [onResult] callback is always delivered on the main thread. Analogue
     * of `HighlightRecorder.swift`'s `saveHighlight(duration:completion:)`.
     */
    fun saveHighlight(context: Context, onResult: (Boolean) -> Unit) {
        val active = service
        if (active == null || !isBuffering) {
            mainHandler.post { onResult(false) }
            return
        }
        active.saveHighlight(context.applicationContext) { success ->
            mainHandler.post { onResult(success) }
        }
    }
}

/**
 * The foreground service that actually performs the rolling capture. See
 * [HighlightRecorder]'s class doc for the honesty note on why this must be a
 * visible `mediaProjection` foreground service on Android.
 *
 * Pipeline: `MediaProjection` -> `VirtualDisplay` mirroring the screen onto a
 * `MediaCodec` (H.264, Surface input) encoder's input surface. The encoder's
 * output samples are copied into an in-memory ring buffer keyed by presentation
 * time; old GOPs are evicted so only ~[HighlightRecorder.RETENTION_US] of
 * encoded video is retained. On save, the buffered samples (trimmed to the last
 * ~15s, starting from a keyframe) are muxed into an .mp4 with `MediaMuxer` and
 * published to `MediaStore.Video`.
 */
class HighlightCaptureService : Service() {

    /** One encoded access unit copied out of the encoder. */
    private class Sample(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val isKeyFrame: Boolean,
    )

    // Ring buffer (guarded by `bufferLock`).
    private val bufferLock = Any()
    private val samples = ArrayList<Sample>()

    /** The encoder's real output format (carries csd-0/csd-1); needed to add
     *  the muxer track. Captured from `onOutputFormatChanged`. */
    @Volatile
    private var outputFormat: MediaFormat? = null

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null

    private var encoderThread: HandlerThread? = null
    private var encoderHandler: Handler? = null

    private var tornDown = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android 14 requires startForeground (with the mediaProjection type) to
        // happen BEFORE getMediaProjection()/createVirtualDisplay(), so do it
        // first, unconditionally.
        startForegroundCompat()

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        if (resultCode == 0 || data == null) {
            // Started without a usable projection token — nothing to do.
            stopSelf()
            return START_NOT_STICKY
        }

        // Already capturing (e.g. a duplicate start) — ignore the new token.
        if (mediaProjection != null) return START_NOT_STICKY

        val started = startCapture(resultCode, data)
        if (!started) {
            // Consent was granted but the encoder/projection couldn't come up —
            // treat it like an involuntary stop so the setting/button correct
            // themselves instead of implying a buffer is running.
            teardown(revoked = true)
            stopSelf()
            return START_NOT_STICKY
        }

        HighlightRecorder.onCaptureStarted(this)
        // NOT sticky: if the process is killed the one-shot projection token is
        // already dead, so a null-intent restart could never resume capture —
        // re-enabling from Settings (which re-prompts for consent) is the only
        // correct recovery path.
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, data: Intent): Boolean {
        return try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val projection = mpm.getMediaProjection(resultCode, data) ?: return false
            mediaProjection = projection

            val thread = HandlerThread("HighlightEncoder").also { it.start() }
            encoderThread = thread
            val handler = Handler(thread.looper)
            encoderHandler = handler

            // Android 14+ mandates a registered MediaProjection.Callback before
            // createVirtualDisplay; it's also how we learn about revocation
            // (the user tapping "Stop" on the system capture notification).
            projection.registerCallback(projectionCallback, handler)

            val (width, height, dpi) = captureDimensions()

            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                // ~1s GOP so the ring buffer is keyframe-aligned at ~1s
                // granularity — a save can always begin muxing from a clean
                // buffered keyframe.
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            val codec = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder = codec
            codec.setCallback(encoderCallback, handler)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = codec.createInputSurface()
            codec.start()

            virtualDisplay = projection.createVirtualDisplay(
                "GBHighlight",
                width,
                height,
                dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                inputSurface,
                null,
                handler,
            )
            true
        } catch (t: Throwable) {
            false
        }
    }

    /** Screen size (capped so the encoder stays light) and density. */
    private fun captureDimensions(): Triple<Int, Int, Int> {
        val metrics = resources.displayMetrics
        var w = metrics.widthPixels
        var h = metrics.heightPixels
        val dpi = if (metrics.densityDpi > 0) metrics.densityDpi else 320
        val longest = max(w, h).toFloat()
        val scale = if (longest > MAX_DIMENSION) MAX_DIMENSION / longest else 1f
        // H.264 wants even dimensions.
        w = max(2, ((w * scale).toInt() / 2) * 2)
        h = max(2, ((h * scale).toInt() / 2) * 2)
        return Triple(w, h, dpi)
    }

    private val encoderCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface input — the framework feeds the encoder, nothing to do.
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                if (!isConfig && !isEos && info.size > 0) {
                    val buf = codec.getOutputBuffer(index)
                    if (buf != null) {
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size)
                        buf.get(bytes)
                        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        appendSample(Sample(bytes, info.presentationTimeUs, isKey))
                    }
                }
                codec.releaseOutputBuffer(index, false)
            } catch (_: IllegalStateException) {
                // Codec was released mid-callback during teardown — ignore.
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            // Encoder died — tear the session down and let the coordinator
            // reflect that buffering stopped unexpectedly.
            teardown(revoked = true)
            stopSelf()
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            outputFormat = format
        }
    }

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            // The user revoked capture via the system notification (or the OS
            // reclaimed it). Tear down and flag it as an involuntary stop.
            teardown(revoked = true)
            stopSelf()
        }
    }

    /** Append a freshly-encoded sample and evict old keyframe-aligned GOPs so
     *  only ~RETENTION_US of video is retained. */
    private fun appendSample(sample: Sample) {
        synchronized(bufferLock) {
            samples.add(sample)
            if (samples.size < 2) return
            val last = samples[samples.size - 1].presentationTimeUs
            // Largest keyframe index whose retained span is still >= RETENTION.
            // Presentation times increase, so once a keyframe falls inside the
            // window every later one does too — stop looking.
            var cut = 0
            for (i in 1 until samples.size) {
                val s = samples[i]
                if (!s.isKeyFrame) continue
                if (last - s.presentationTimeUs >= HighlightRecorder.RETENTION_US) cut = i else break
            }
            if (cut > 0) samples.subList(0, cut).clear()
        }
    }

    /**
     * Snapshot the buffer (trimmed to the last ~15s, keyframe-aligned), mux it
     * to a temp .mp4, then publish it to MediaStore.Video on a worker thread.
     * [onResult] is invoked on that worker thread (the coordinator re-posts it
     * to main).
     */
    fun saveHighlight(context: Context, onResult: (Boolean) -> Unit) {
        // Definite-assignment (rather than a smart-cast narrowed inside the
        // synchronized{} lambda) so `format`'s non-null type is guaranteed by
        // the compiler itself when read later from the Thread{} closure below.
        val format: MediaFormat
        val clip: List<Sample>
        synchronized(bufferLock) {
            val fmt = outputFormat
            if (samples.isEmpty() || fmt == null) {
                onResult(false)
                return
            }
            format = fmt
            val last = samples[samples.size - 1].presentationTimeUs
            // Start from the newest keyframe whose retained span is still
            // >= SAVE_US (i.e. keep ~15s), so muxing begins on a clean keyframe.
            var start = 0
            for (i in samples.indices) {
                val s = samples[i]
                if (!s.isKeyFrame) continue
                if (last - s.presentationTimeUs >= HighlightRecorder.SAVE_US) start = i else break
            }
            // Copy so the worker thread is decoupled from ongoing eviction.
            clip = ArrayList(samples.subList(start, samples.size))
        }

        Thread {
            val ok = try {
                muxAndPublish(context, format, clip)
            } catch (t: Throwable) {
                false
            }
            onResult(ok)
        }.start()
    }

    private fun muxAndPublish(context: Context, format: MediaFormat, clip: List<Sample>): Boolean {
        if (clip.isEmpty()) return false

        val temp = File.createTempFile("highlight-", ".mp4", context.cacheDir)
        try {
            val muxer = MediaMuxer(temp.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val track = muxer.addTrack(format)
            muxer.start()
            val firstPts = clip.first().presentationTimeUs
            val info = MediaCodec.BufferInfo()
            for (s in clip) {
                val bb = ByteBuffer.wrap(s.data)
                val flags = if (s.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, s.data.size, s.presentationTimeUs - firstPts, flags)
                muxer.writeSampleData(track, bb, info)
            }
            muxer.stop()
            muxer.release()

            return publishToGallery(context, temp)
        } finally {
            temp.delete()
        }
    }

    /** Copy the muxed clip into the shared gallery (the Android analogue of
     *  iOS's PHPhotoLibrary save). */
    private fun publishToGallery(context: Context, source: File): Boolean {
        val displayName = "GameBrowser-${System.currentTimeMillis()}.mp4"

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29+: scoped storage — insert into MediaStore under
            // Movies/GameBrowser with IS_PENDING, stream the bytes in, then
            // publish. No storage permission required.
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/GameBrowser")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri: Uri = resolver.insert(collection, values) ?: return false
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                } ?: return false
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                true
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                false
            }
        } else {
            // API 26-28: legacy public-directory write + media-scan so the clip
            // shows up in the gallery. NOTE: writing to the public Movies dir on
            // these versions requires the WRITE_EXTERNAL_STORAGE runtime
            // permission (see INTEGRATION STEPS) — without it this copy fails
            // and the save reports failure (surfaced as the button's "Failed"
            // state), which is the honest outcome.
            @Suppress("DEPRECATION")
            val moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val dir = File(moviesDir, "GameBrowser").apply { mkdirs() }
            val dest = File(dir, displayName)
            try {
                source.inputStream().use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), arrayOf("video/mp4"), null)
                true
            } catch (t: Throwable) {
                dest.delete()
                false
            }
        }
    }

    private fun teardown(revoked: Boolean) {
        if (tornDown) return
        tornDown = true

        try { virtualDisplay?.release() } catch (_: Throwable) {}
        virtualDisplay = null

        try { encoder?.stop() } catch (_: Throwable) {}
        try { encoder?.release() } catch (_: Throwable) {}
        encoder = null

        try { inputSurface?.release() } catch (_: Throwable) {}
        inputSurface = null

        mediaProjection?.let { proj ->
            try { proj.unregisterCallback(projectionCallback) } catch (_: Throwable) {}
            try { proj.stop() } catch (_: Throwable) {}
        }
        mediaProjection = null

        encoderThread?.quitSafely()
        encoderThread = null
        encoderHandler = null

        synchronized(bufferLock) { samples.clear() }
        outputFormat = null

        HighlightRecorder.onCaptureStopped(this, revoked)
    }

    override fun onDestroy() {
        // A plain stopService (user disabling the feature) is a voluntary stop,
        // not a revocation.
        teardown(revoked = false)
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GameBrowser instant replay")
            .setContentText("Buffering the last 15 seconds of gameplay")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Instant replay",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while GameBrowser is buffering gameplay for instant replay"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "highlight_capture"
        private const val NOTIFICATION_ID = 43
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"

        private const val MIME_TYPE = "video/avc"
        private const val BIT_RATE = 6_000_000
        private const val FRAME_RATE = 30
        private const val MAX_DIMENSION = 1280f

        /** Start the capture service with a granted projection token. */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, HighlightCaptureService::class.java).apply {
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Stop the capture service, dropping its foreground notification. */
        fun stop(context: Context) {
            context.stopService(Intent(context, HighlightCaptureService::class.java))
        }
    }
}
