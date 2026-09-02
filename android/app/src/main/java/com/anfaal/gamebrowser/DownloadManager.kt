package com.anfaal.gamebrowser

import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat

/**
 * Downloads, the Android half of DownloadManager.swift. The app could not save
 * a file at all before: tapping a download link either did nothing or handed
 * the WebView something it cannot render, so game mods, save files and
 * screenshots were out of reach.
 *
 * Where iOS drives WKDownload itself, this hands the transfer to the system
 * download service, which is the platform's answer to the same problem: it
 * survives the app being killed, retries across a dropped connection, and puts
 * the file in the shared Downloads folder where every other app can open it.
 * That service is also the source of truth for the list, so a file deleted
 * elsewhere doesn't linger here - the same property the iOS version gets from
 * reading its folder back.
 */
class DownloadManager(context: Context) {

    /** One download - live or already on disk. Mirrors DownloadManager.Item. */
    class Item(
        val systemId: Long,
        filename: String,
        val source: String?,
        state: State = State.Downloading,
        var fileUri: Uri? = null,
        var date: Long = System.currentTimeMillis(),
        bytes: Long = 0,
        downloaded: Long = 0,
    ) {
        sealed class State {
            object Downloading : State()
            object Finished : State()
            object Cancelled : State()
            data class Failed(val message: String) : State()
        }

        var filename by mutableStateOf(filename)
        var state by mutableStateOf(state)
        var bytes by mutableStateOf(bytes)
        var downloaded by mutableStateOf(downloaded)

        val isActive: Boolean get() = state is State.Downloading

        val progress: Float
            get() = if (bytes > 0) (downloaded.toFloat() / bytes).coerceIn(0f, 1f) else 0f

        val sizeText: String
            get() = if (bytes > 0) formatBytes(bytes) else ""
    }

    private val appContext = context.applicationContext
    private val system =
        appContext.getSystemService(Context.DOWNLOAD_SERVICE) as android.app.DownloadManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null

    private val _items = mutableStateListOf<Item>()
    val items: List<Item> get() = _items

    /** Set when a download finishes, for the toast. */
    var lastFinished by mutableStateOf<String?>(null)

    /** Called once per download that completes while the app is running. */
    var onFinished: ((String) -> Unit)? = null

    /**
     * Tracked separately rather than computed: an item finishing changes its
     * own state, not the list, so a computed count would not recompose.
     */
    var activeCount by mutableStateOf(0)
        private set

    private fun refreshActiveCount() {
        activeCount = _items.count { it.isActive }
    }

    // MARK: - Starting

    /**
     * Queue [url] for download. Returns false when the URL is not something the
     * system service can fetch (`blob:`/`data:` and friends), so the caller can
     * say so rather than appearing to do nothing.
     */
    fun start(
        url: String,
        userAgent: String? = null,
        contentDisposition: String? = null,
        mimeType: String? = null,
        contentLength: Long = 0,
        referer: String? = null,
    ): Boolean {
        if (!URLUtil.isNetworkUrl(url)) return false
        val name = try {
            URLUtil.guessFileName(url, contentDisposition, mimeType)
        } catch (e: Exception) {
            "download"
        }
        val request = try {
            android.app.DownloadManager.Request(Uri.parse(url))
        } catch (e: Exception) {
            return false
        }
        request.setTitle(name)
        request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
        request.setNotificationVisibility(
            android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED,
        )
        if (!mimeType.isNullOrEmpty()) request.setMimeType(mimeType)
        if (!userAgent.isNullOrEmpty()) request.addRequestHeader("User-Agent", userAgent)
        if (!referer.isNullOrEmpty()) request.addRequestHeader("Referer", referer)
        // Most game files sit behind a login, so the page's cookies have to go
        // with the request - the download service has its own cookie jar.
        try {
            val cookie = CookieManager.getInstance().getCookie(url)
            if (!cookie.isNullOrEmpty()) request.addRequestHeader("Cookie", cookie)
        } catch (e: Exception) {
            // No cookie store yet; the request just goes out without one.
        }

        val id = try {
            system.enqueue(request)
        } catch (e: Exception) {
            return false
        }
        _items.add(0, Item(systemId = id, filename = name, source = url, bytes = contentLength))
        refreshActiveCount()
        startPolling()
        return true
    }

    // MARK: - Progress

    /**
     * The system service has no change callback, so live rows are polled while
     * any of them is running and the loop stops as soon as none is.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (_items.any { it.isActive }) {
                refreshFromSystem()
                delay(POLL_INTERVAL_MS)
            }
            refreshFromSystem()
        }
    }

    private fun refreshFromSystem() {
        val byId = query()
        for (item in _items) {
            val row = byId[item.systemId] ?: continue
            item.filename = row.filename ?: item.filename
            if (row.total > 0) item.bytes = row.total
            item.downloaded = row.downloaded
            item.fileUri = row.localUri
            val next = row.state
            if (item.state !is Item.State.Cancelled || next !is Item.State.Downloading) {
                val wasActive = item.isActive
                item.state = next
                if (wasActive && next is Item.State.Finished) {
                    item.date = row.lastModified
                    lastFinished = item.filename
                    onFinished?.invoke(item.filename)
                }
            }
        }
        refreshActiveCount()
    }

    private class Row(
        val id: Long,
        val filename: String?,
        val total: Long,
        val downloaded: Long,
        val state: Item.State,
        val localUri: Uri?,
        val source: String?,
        val lastModified: Long,
    )

    private fun query(): Map<Long, Row> {
        val rows = HashMap<Long, Row>()
        var cursor: Cursor? = null
        try {
            cursor = system.query(android.app.DownloadManager.Query())
            if (cursor == null) return rows
            val idCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_ID)
            val titleCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TITLE)
            val statusCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_STATUS)
            val reasonCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_REASON)
            val totalCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
            val soFarCol =
                cursor.getColumnIndex(android.app.DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
            val localCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_LOCAL_URI)
            val uriCol = cursor.getColumnIndex(android.app.DownloadManager.COLUMN_URI)
            val timeCol =
                cursor.getColumnIndex(android.app.DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val localUriString = if (localCol >= 0) cursor.getString(localCol) else null
                val localUri = localUriString?.let { runCatching { Uri.parse(it) }.getOrNull() }
                val status = if (statusCol >= 0) cursor.getInt(statusCol) else 0
                val reason = if (reasonCol >= 0) cursor.getInt(reasonCol) else 0
                rows[id] = Row(
                    id = id,
                    filename = localUri?.lastPathSegment
                        ?: (if (titleCol >= 0) cursor.getString(titleCol) else null),
                    total = if (totalCol >= 0) cursor.getLong(totalCol) else 0,
                    downloaded = if (soFarCol >= 0) cursor.getLong(soFarCol) else 0,
                    state = stateFor(status, reason),
                    localUri = localUri,
                    source = if (uriCol >= 0) cursor.getString(uriCol) else null,
                    lastModified = if (timeCol >= 0) cursor.getLong(timeCol)
                    else System.currentTimeMillis(),
                )
            }
        } catch (e: Exception) {
            // The provider can be unavailable (a restricted profile, a device
            // with the download service disabled); the list just doesn't move.
        } finally {
            try { cursor?.close() } catch (e: Exception) { }
        }
        return rows
    }

    private fun stateFor(status: Int, reason: Int): Item.State = when (status) {
        android.app.DownloadManager.STATUS_SUCCESSFUL -> Item.State.Finished
        android.app.DownloadManager.STATUS_FAILED -> Item.State.Failed(failureText(reason))
        else -> Item.State.Downloading
    }

    private fun failureText(reason: Int): String = when (reason) {
        android.app.DownloadManager.ERROR_INSUFFICIENT_SPACE ->
            loc("空き容量が足りません", "Not enough space")
        android.app.DownloadManager.ERROR_DEVICE_NOT_FOUND ->
            loc("保存先が見つかりません", "No storage available")
        android.app.DownloadManager.ERROR_CANNOT_RESUME ->
            loc("再開できませんでした", "Could not resume")
        android.app.DownloadManager.ERROR_HTTP_DATA_ERROR ->
            loc("通信エラー", "Connection error")
        else -> loc("ダウンロードに失敗しました", "Download failed")
    }

    /**
     * Rebuild the list from the download service, which knows about everything
     * this app has ever queued - including downloads that finished while the
     * app was not running.
     */
    fun loadFromDisk() {
        val rows = query().values.sortedByDescending { it.lastModified }
        val live = _items.filter { it.isActive }.associateBy { it.systemId }
        val rebuilt = rows.map { row ->
            live[row.id]?.also {
                it.filename = row.filename ?: it.filename
                if (row.total > 0) it.bytes = row.total
                it.downloaded = row.downloaded
                it.state = row.state
                it.fileUri = row.localUri
            } ?: Item(
                systemId = row.id,
                filename = row.filename ?: "download",
                source = row.source,
                state = row.state,
                fileUri = row.localUri,
                date = row.lastModified,
                bytes = row.total,
                downloaded = row.downloaded,
            )
        }
        _items.clear()
        _items.addAll(rebuilt)
        refreshActiveCount()
        if (_items.any { it.isActive }) startPolling()
    }

    // MARK: - Actions

    fun cancel(item: Item) {
        try { system.remove(item.systemId) } catch (e: Exception) { }
        item.state = Item.State.Cancelled
        refreshActiveCount()
    }

    /** Removes the row and the file with it, same as the iOS list's swipe-delete. */
    fun delete(item: Item) {
        try { system.remove(item.systemId) } catch (e: Exception) { }
        _items.remove(item)
        refreshActiveCount()
    }

    fun clearFinished() {
        val finished = _items.filter { !it.isActive }
        for (item in finished) {
            try { system.remove(item.systemId) } catch (e: Exception) { }
        }
        _items.removeAll(finished)
        refreshActiveCount()
    }

    /**
     * A `content://` URI another app can actually open. The service's own
     * `file://` URI would throw FileUriExposedException the moment it left the
     * app, so it is republished through the FileProvider.
     */
    fun shareIntent(item: Item): Intent? {
        val uri = item.fileUri ?: return null
        val shareUri = when (uri.scheme) {
            "file" -> {
                val path = uri.path ?: return null
                try {
                    FileProvider.getUriForFile(
                        appContext,
                        "${appContext.packageName}.fileprovider",
                        File(path),
                    )
                } catch (e: Exception) {
                    return null
                }
            }
            else -> uri
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun dispose() {
        pollJob?.cancel()
        scope.cancel()
    }

    companion object {
        private const val POLL_INTERVAL_MS = 500L

        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return ""
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            var value = bytes.toDouble()
            var unit = 0
            while (value >= 1024 && unit < units.lastIndex) {
                value /= 1024
                unit++
            }
            val format = if (unit == 0 || value >= 100) DecimalFormat("#") else DecimalFormat("#.#")
            return "${format.format(value)} ${units[unit]}"
        }
    }
}
