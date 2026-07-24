package com.anfaal.gamebrowser

import android.content.Context
import android.util.Log
import android.webkit.WebResourceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Ad blocking + tracker blocking, ported from GameBrowser/Sources/AdBlocker.swift.
 *
 * iOS drives this via WKContentRuleList — WKWebView's declarative content-blocking
 * engine (the same one Safari content blockers use): a JSON rule list is compiled
 * once and then applied natively to every request, including a "css-display-none"
 * rule for cosmetic hiding. Android's WebView has no such engine, so this port uses
 * two different mechanisms instead (both wired up from GameWebView.kt — see that
 * file's integration for the call sites):
 *
 *  1. Network blocking: [shouldBlock] is meant to be called from
 *     `WebViewClient.shouldInterceptRequest` for every resource request. When it
 *     returns true, the caller should return [blockedResponse] instead of null
 *     (returning null lets the request through unmodified).
 *  2. Cosmetic hiding: [cosmeticHidingScript] is a JS snippet to evaluate at
 *     document-start (the same injection point GameWebView.kt uses for
 *     input_bridge.js) that hides known ad-container selectors immediately and
 *     keeps hiding new ones via a MutationObserver, since Android has no
 *     declarative "apply this CSS at load time" hook the way css-display-none does.
 *
 * Both [AdBlocker] and [TrackerBlocker] below mirror their iOS namesakes 1:1 in
 * their domain lists; only the enforcement mechanism differs.
 */

// ---------------------------------------------------------------------------
// Shared helpers (used by both AdBlocker and TrackerBlocker below).
// ---------------------------------------------------------------------------

/**
 * Turns a regex-escaped-dot domain pattern like "doubleclick\.net" or
 * "i-mobile\.co\.jp" (the format both domain lists below use, copied verbatim
 * from AdBlocker.swift/TrackerBlocker.swift) into a plain domain string. This is
 * a literal (non-regex) replace of the two-character sequence "\." with ".".
 */
private fun unescapeDomainPattern(pattern: String): String =
    pattern.replace("\\.", ".").lowercase(Locale.ROOT)

/**
 * True if [host] equals one of [domains], or is a subdomain of one of them.
 *
 * This is the host-matching equivalent of AdBlocker.swift's `domainURLFilter(_:)`
 * anchoring: matching is boundary-checked on '.' so that, e.g., a blocked domain
 * "amazon-adsystem.com" matches "ads.amazon-adsystem.com" but NOT
 * "amazon-adsystem.company.com" (that host does not end in ".amazon-adsystem.com",
 * even though "amazon-adsystem.com" would appear as an unanchored *substring* of
 * it — "company" starts with "com", so a naive unanchored check on
 * "amazon-adsystem.com" would wrongly match "amazon-adsystem.company.com"). This
 * is the exact bug domainURLFilter's `(?:[:/]|$)` trailing anchor fixes on iOS;
 * walking '.'-delimited suffixes here achieves the same anchoring without
 * needing to compile a regex per domain.
 *
 * [host] must already be lowercased by the caller. Implemented as a suffix walk
 * (host, then each parent domain) against a hash set rather than iterating every
 * domain's own check, so it stays fast even for the tens-of-thousands-of-entries
 * EasyList set ([AdBlocker.currentFullListDomains]) — O(number of labels in the
 * host) hash lookups instead of O(number of blocked domains) comparisons.
 */
private fun isHostBlockedBySet(host: String, domains: Set<String>): Boolean {
    if (domains.isEmpty()) return false
    var rest = host
    while (true) {
        if (domains.contains(rest)) return true
        val dot = rest.indexOf('.')
        if (dot < 0) return false
        rest = rest.substring(dot + 1)
    }
}

// ---------------------------------------------------------------------------
// EasyList (full-list) parsing — shared constants/helpers.
// ---------------------------------------------------------------------------

/**
 * The exact prefix/suffix AdBlocker.swift's `domainURLFilter(_:)` wraps every
 * domain pattern in: `"^https?://([^/]+\.)?" + domain + "(?:[:/]|$)"`. EasyList's
 * own Safari-content-blocker JSON (see [AdBlocker.refreshFullListIfNeeded]) uses
 * this identical shape for the vast majority of its per-domain blocking rules, so
 * recognizing it lets us extract the plain domain back out without touching a
 * regex engine at all — see [extractDomainFromUrlFilter].
 */
private const val URL_FILTER_PREFIX = "^https?://([^/]+\\.)?"
private const val URL_FILTER_SUFFIX = "(?:[:/]|$)"

/**
 * True if [pattern] is *only* letters/digits/hyphens with literal dots written as
 * the regex-escaped "\." the domain lists in this file use (e.g. "doubleclick\.net",
 * "i-mobile\.co\.jp") — i.e. it is exactly the shape [unescapeDomainPattern] knows
 * how to turn into a plain domain, nothing fancier. Used to reject EasyList
 * url-filters that happen to share [URL_FILTER_PREFIX]/[URL_FILTER_SUFFIX] but pack
 * real regex machinery (wildcards, character classes, alternation) into the middle,
 * which we do not attempt to translate — see the SIMPLIFICATION note on
 * [AdBlocker.refreshFullListIfNeeded].
 */
private fun looksLikePlainDomainPattern(pattern: String): Boolean {
    var i = 0
    var sawLabelChar = false
    while (i < pattern.length) {
        val c = pattern[i]
        if (c == '\\' && i + 1 < pattern.length && pattern[i + 1] == '.') {
            i += 2
            sawLabelChar = false
        } else if (c.isLetterOrDigit() || c == '-') {
            i += 1
            sawLabelChar = true
        } else {
            return false
        }
    }
    return sawLabelChar
}

/**
 * Extracts the plain blocked domain from one EasyList rule's `trigger.url-filter`
 * string, or null if it isn't the simple anchored-domain shape [URL_FILTER_PREFIX]/
 * [URL_FILTER_SUFFIX] describe (see [looksLikePlainDomainPattern]).
 */
private fun extractDomainFromUrlFilter(urlFilter: String): String? {
    if (urlFilter.length < URL_FILTER_PREFIX.length + URL_FILTER_SUFFIX.length) return null
    if (!urlFilter.startsWith(URL_FILTER_PREFIX) || !urlFilter.endsWith(URL_FILTER_SUFFIX)) return null
    val middle = urlFilter.substring(URL_FILTER_PREFIX.length, urlFilter.length - URL_FILTER_SUFFIX.length)
    if (middle.isEmpty() || !looksLikePlainDomainPattern(middle)) return null
    return unescapeDomainPattern(middle)
}

/**
 * Ad blocking. Builtin domain list + cosmetic selectors ported verbatim from
 * AdBlocker.swift, plus optional full-EasyList mode (see [refreshFullListIfNeeded]).
 */
object AdBlocker {

    // -------------------------------------------------------------------
    // Builtin list (ported 1:1 from AdBlocker.swift's `blockedDomains`).
    // -------------------------------------------------------------------

    /** Third-party ad / tracking networks blocked outright. */
    private val blockedDomainPatterns = listOf(
        "doubleclick\\.net",
        "googlesyndication\\.com",
        "googleadservices\\.com",
        "adservice\\.google\\.com",
        "google-analytics\\.com",
        "googletagmanager\\.com",
        "googletagservices\\.com",
        "amazon-adsystem\\.com",
        "adnxs\\.com",
        "criteo\\.com",
        "criteo\\.net",
        "taboola\\.com",
        "outbrain\\.com",
        "pubmatic\\.com",
        "rubiconproject\\.com",
        "openx\\.net",
        "adsrvr\\.org",
        "smartadserver\\.com",
        "moatads\\.com",
        "scorecardresearch\\.com",
        "zedo\\.com",
        "popads\\.net",
        "propellerads\\.com",
        "adsterra\\.com",
        "exoclick\\.com",
        "juicyads\\.com",
        "mgid\\.com",
        "revcontent\\.com",
        "yieldmo\\.com",
        "sharethrough\\.com",
        "adform\\.net",
        "casalemedia\\.com",
        "33across\\.com",
        "gumgum\\.com",
        "bidswitch\\.net",
        "advertising\\.com",
        "adcolony\\.com",
        "unityads\\.unity3d\\.com",
        "applovin\\.com",
        "i-mobile\\.co\\.jp",
        "adingo\\.jp",
        "fluct\\.jp",
        "impact-ad\\.jp",
        "microad\\.co\\.jp",
        "nend\\.net",
        "zucks\\.co\\.jp",
        "gsspat\\.jp",
        "gssprt\\.jp",
        "deqwas\\.net",
        "socdm\\.com",
        "adfurikun\\.jp",
    )

    /** Common ad container selectors hidden cosmetically. */
    private val hiddenSelectors = listOf(
        "ins.adsbygoogle",
        "[id^='google_ads_']",
        "[id^='div-gpt-ad']",
        "iframe[src*='doubleclick.net']",
        "iframe[src*='googlesyndication.com']",
        ".ad-banner",
        ".ad-container",
        ".ad-wrapper",
        ".ad-placeholder",
        "[class*='taboola']",
        "[id*='taboola']",
        "[class*='outbrain']",
        "[id*='outbrain']",
    )

    /** Plain-domain form of [blockedDomainPatterns], built once. */
    private val builtinBlockedDomains: Set<String> by lazy {
        blockedDomainPatterns.map { unescapeDomainPattern(it) }.toHashSet()
    }

    /**
     * Builds a url-filter regex matching the domain or any of its subdomains,
     * anchored right after the domain so an unrelated domain that merely starts
     * with the same characters (e.g. "amazon-adsystem.company.com") can't match
     * "amazon-adsystem.com". Direct port of AdBlocker.swift's `domainURLFilter(_:)`
     * — kept here for fidelity/documentation and reused by [extractDomainFromUrlFilter]
     * to recognize the same shape in downloaded EasyList rules. The actual
     * per-request host check in [shouldBlock] does NOT compile/run this regex (that
     * would be far too slow once the EasyList set has tens of thousands of entries)
     * — it uses the equivalent-but-faster [isHostBlockedBySet] suffix-set lookup
     * instead. IMPORTANT: this anchoring (particularly the trailing
     * `(?:[:/]|$)`) is a deliberate bug fix vs. an earlier unanchored version —
     * do not drop it if this function is ever repurposed.
     */
    fun domainURLFilter(domainPattern: String): String =
        "^https?://([^/]+\\.)?$domainPattern(?:[:/]|$)"

    // -------------------------------------------------------------------
    // Full EasyList mode.
    // -------------------------------------------------------------------

    private const val FULL_LIST_URL =
        "https://easylist-downloads.adblockplus.org/easylist_content_blocker.json"
    private const val CACHE_FILE_NAME = "adblock_easylist_cache.json"
    private const val PREFS_NAME = "com.anfaal.gamebrowser.adblock"
    private const val PREF_LAST_FETCH_AT = "easylist_fetched_at"
    private val REFRESH_INTERVAL_MS = TimeUnit.DAYS.toMillis(7)
    private const val TAG = "AdBlocker"

    /** In-memory copy of the parsed EasyList set; empty until first successfully loaded. */
    @Volatile
    private var fullListCache: Set<String> = emptySet()

    /**
     * Current in-memory EasyList-derived blocked-host set. Empty until
     * [refreshFullListIfNeeded] has completed at least once (e.g. at app startup, or
     * when the user turns on the "full ad list" setting). Safe to call from any
     * thread, including from `shouldInterceptRequest` — this does no I/O, unlike
     * [refreshFullListIfNeeded].
     */
    fun currentFullListDomains(): Set<String> = fullListCache

    /**
     * Downloads (or reuses the cached, weekly-refreshed copy of) EasyList's Safari
     * content-blocker JSON and parses it into a plain set of blocked hostnames,
     * mirroring AdBlocker.swift's `fullRuleList()`: refresh at most once a week,
     * fall back to whatever is cached on disk if the network fetch fails, and fall
     * back further to the builtin list (i.e. an empty full-list set, since
     * [AdBlocker.shouldBlock] always also checks [builtinBlockedDomains]) if
     * nothing has ever been fetched successfully.
     *
     * SIMPLIFICATION: the downloaded JSON is in WKContentRuleList's Safari
     * content-blocker format — a small rule-matching *language* (regex url-filters,
     * load-type/resource-type conditions, if-domain/unless-domain scoping,
     * "css-display-none" selector actions, etc.), not just a domain list.
     * Reimplementing that whole engine on Android is out of scope. Instead, this
     * only looks at rules whose `action.type == "block"` and whose
     * `trigger.url-filter` has the exact anchored-domain shape
     * `domainURLFilter(_:)` itself produces
     * (`"^https?://([^/]+\.)?<domain>(?:[:/]|$)"`, see [extractDomainFromUrlFilter]),
     * which covers the large majority of EasyList's per-domain blocking rules, and
     * pulls `<domain>` out of it into a plain `Set<String>`. Rules with more
     * elaborate url-filters (character classes, alternation, path-scoped patterns,
     * domain-scoping conditions, cosmetic "css-display-none" rules, etc.) don't
     * match that exact shape and are silently skipped rather than mistranslated.
     * This trades perfect EasyList fidelity for a simple, fast, host-set-based
     * blocklist that fits the same "block by host" model
     * `WebViewClient.shouldInterceptRequest` can actually act on per request
     * (it has no way to evaluate arbitrary path/regex/scoped rules cheaply).
     *
     * Does blocking network + file I/O — must be called off the main thread (this
     * function hops to [Dispatchers.IO] itself, so it's safe to call directly from
     * a coroutine launched on any dispatcher, e.g. `viewModelScope.launch { ... }`).
     * Do NOT call this from `shouldInterceptRequest` — read [currentFullListDomains]
     * there instead.
     *
     * @param forceRefresh bypass the weekly-freshness check (e.g. a manual
     *   "refresh block list" action in settings).
     */
    suspend fun refreshFullListIfNeeded(context: Context, forceRefresh: Boolean = false): Set<String> =
        withContext(Dispatchers.IO) {
            refreshFullListBlocking(context.applicationContext, forceRefresh)
        }

    /** Synchronous body of [refreshFullListIfNeeded]; `@Synchronized` so overlapping calls
     *  (e.g. one from app startup, one from a settings toggle) don't race on [fullListCache]
     *  or the cache file. Only ever called from the [Dispatchers.IO]-confined coroutine above. */
    @Synchronized
    private fun refreshFullListBlocking(appContext: Context, forceRefresh: Boolean): Set<String> {
        val cacheFile = File(appContext.filesDir, CACHE_FILE_NAME)
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFetchAt = prefs.getLong(PREF_LAST_FETCH_AT, 0L)
        val fresh = !forceRefresh &&
            (System.currentTimeMillis() - lastFetchAt) < REFRESH_INTERVAL_MS

        if (fresh && fullListCache.isNotEmpty()) {
            return fullListCache
        }
        if (fresh) {
            loadCacheFile(cacheFile)?.let {
                fullListCache = it
                return it
            }
        }

        val downloaded = downloadAndParseFullList()
        if (downloaded != null && downloaded.isNotEmpty()) {
            fullListCache = downloaded
            saveCacheFile(cacheFile, downloaded)
            prefs.edit().putLong(PREF_LAST_FETCH_AT, System.currentTimeMillis()).apply()
            return downloaded
        }

        // Offline / EasyList unreachable / gone: any previously cached copy
        // (in memory, or on disk from a previous run) is still valid — same
        // stale-cache fallback as AdBlocker.swift's fullRuleList().
        if (fullListCache.isNotEmpty()) return fullListCache
        val stale = loadCacheFile(cacheFile)
        if (stale != null) {
            fullListCache = stale
            return stale
        }

        // Never fetched successfully and nothing cached on disk: leave
        // fullListCache empty. Callers still get builtin-list protection via
        // AdBlocker.shouldBlock; "full list" mode just has nothing extra yet.
        return emptySet()
    }

    private fun downloadAndParseFullList(): Set<String>? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(FULL_LIST_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            if (body.isBlank()) null else parseContentBlockerJson(body)
        } catch (e: Exception) {
            Log.w(TAG, "EasyList fetch failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** See the SIMPLIFICATION note on [refreshFullListIfNeeded]. */
    private fun parseContentBlockerJson(json: String): Set<String>? {
        return try {
            val array = JSONArray(json)
            val domains = HashSet<String>(array.length())
            for (i in 0 until array.length()) {
                val rule = array.optJSONObject(i) ?: continue
                val action = rule.optJSONObject("action") ?: continue
                if (action.optString("type") != "block") continue
                val trigger = rule.optJSONObject("trigger") ?: continue
                val urlFilter = trigger.optString("url-filter", "")
                if (urlFilter.isEmpty()) continue
                extractDomainFromUrlFilter(urlFilter)?.let { domains.add(it) }
            }
            if (domains.isEmpty()) null else domains
        } catch (e: Exception) {
            Log.w(TAG, "EasyList JSON parse failed: ${e.message}")
            null
        }
    }

    private fun loadCacheFile(file: File): Set<String>? {
        if (!file.exists()) return null
        return try {
            val array = JSONArray(file.readText(Charsets.UTF_8))
            val set = HashSet<String>(array.length())
            for (i in 0 until array.length()) set.add(array.getString(i))
            if (set.isEmpty()) null else set
        } catch (e: Exception) {
            null
        }
    }

    private fun saveCacheFile(file: File, domains: Set<String>) {
        try {
            val array = JSONArray()
            domains.forEach { array.put(it) }
            file.writeText(array.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write EasyList cache: ${e.message}")
        }
    }

    // -------------------------------------------------------------------
    // Cosmetic hiding.
    // -------------------------------------------------------------------

    /**
     * Cosmetic ad-hiding JS, ported from AdBlocker.swift's "css-display-none"
     * content-blocker rule (which hides [hiddenSelectors] declaratively via
     * WKContentRuleList). Android WebView has no declarative equivalent, so this
     * evaluates the hide immediately against whatever's already in the DOM, then
     * installs a MutationObserver that re-applies it to anything added later (ads
     * frequently lazy-load / re-render after the initial paint). Guarded by a
     * `window.__gbAdblockCosmetic` flag the same way input_bridge.js guards itself
     * with `window.__gb`, so re-injecting on every `onPageStarted` is harmless.
     *
     * Inject this the same way GameWebView.kt injects input_bridge.js —
     * `webView.evaluateJavascript(AdBlocker.cosmeticHidingScript, null)` from
     * `WebViewClient.onPageStarted`, guarded by the same `adBlockEnabled` state
     * that gates [shouldBlock] (see the INTEGRATION STEPS in the porting report
     * for the exact call site).
     */
    val cosmeticHidingScript: String by lazy {
        val selectorList = hiddenSelectors.joinToString(", ")
        // org.json.JSONObject.quote() produces a fully-escaped double-quoted
        // string literal (backslashes, quotes, control chars) — JSON string
        // literal syntax is a subset of JS string literal syntax, so it's safe
        // to splice directly into the script below.
        val selectorsLiteral = JSONObject.quote(selectorList)
        """
        (function () {
            if (window.__gbAdblockCosmetic) { return; }
            window.__gbAdblockCosmetic = true;
            var SELECTORS = $selectorsLiteral;
            function hide(root) {
                try {
                    root.querySelectorAll(SELECTORS).forEach(function (el) {
                        el.style.setProperty('display', 'none', 'important');
                    });
                } catch (e) {}
            }
            function start() {
                hide(document);
                try {
                    var observer = new MutationObserver(function () { hide(document); });
                    observer.observe(document.documentElement || document.body, {
                        childList: true,
                        subtree: true,
                    });
                } catch (e) {}
            }
            if (document.body) {
                start();
            } else {
                document.addEventListener('DOMContentLoaded', start);
            }
        })();
        """.trimIndent()
    }

    // -------------------------------------------------------------------
    // The actual per-request check.
    // -------------------------------------------------------------------

    /**
     * True if [requestHost] is a different site than [pageHost] (the main frame's
     * own host — read `view.url` in `WebViewClient.shouldInterceptRequest`/
     * `onPageStarted` and pass its host through here). A host equal to, or a
     * subdomain of, the page's own host counts as first-party (and vice versa, so
     * a page on "sub.example.com" loading something from "example.com" is still
     * first-party) — same boundary-checked suffix relationship
     * [isHostBlockedBySet] and AutofillStore.credentials(...) use elsewhere in
     * this app, not a naive `endsWith` that could be fooled by a domain that
     * merely shares a suffix.
     */
    fun isThirdPartyHost(requestHost: String?, pageHost: String?): Boolean {
        if (requestHost.isNullOrEmpty() || pageHost.isNullOrEmpty()) return true
        val r = requestHost.lowercase(Locale.ROOT)
        val p = pageHost.lowercase(Locale.ROOT)
        if (r == p) return false
        if (r.endsWith(".$p") || p.endsWith(".$r")) return false
        return true
    }

    /**
     * Master "should this network request be blocked" check. Call this from
     * `WebViewClient.shouldInterceptRequest` for every resource request and
     * return [blockedResponse] when it's true (returning null lets the request
     * through normally).
     *
     * @param host the request's URL host, e.g. `request.url.host`.
     * @param isThirdParty whether [host] is a different site than the page's own
     *   host — see [isThirdPartyHost]. Ad-domain blocking (both the builtin list
     *   and, when enabled, the full EasyList set) is scoped to third-party loads
     *   only, matching AdBlocker.swift's `rulesJSON`, whose trigger for every
     *   `blockedDomains` entry includes `"load-type": ["third-party"]` — i.e. iOS
     *   only blocks these hosts as a sub-resource of some other page, never when
     *   the user navigates to one of them directly. That scoping is applied
     *   uniformly to the EasyList-derived set too (rather than trying to honor
     *   each individual EasyList rule's own load-type condition, which the
     *   simplified parser in [refreshFullListIfNeeded] does not retain) — this
     *   errs on the side of never blocking the page the user is actually visiting.
     * @param adBlockEnabled whether ad-domain blocking is on at all.
     * @param useFullAdList whether to additionally consult the downloaded EasyList
     *   set ([currentFullListDomains]) on top of the builtin list.
     * @param trackingLevel one of [TrackerBlocker.OFF] / [TrackerBlocker.BALANCED]
     *   / [TrackerBlocker.STRICT]. Balanced blocks tracker domains only on
     *   third-party loads (like the builtin ad list); strict blocks them
     *   regardless of load type — matching TrackerBlocker.swift's
     *   `rulesJSON(for:)`.
     */
    fun shouldBlock(
        host: String?,
        isThirdParty: Boolean,
        adBlockEnabled: Boolean,
        useFullAdList: Boolean = false,
        trackingLevel: Int = TrackerBlocker.OFF,
    ): Boolean {
        val lowerHost = host?.lowercase(Locale.ROOT)
        if (lowerHost.isNullOrEmpty()) return false

        if (adBlockEnabled && isThirdParty) {
            if (isHostBlockedBySet(lowerHost, builtinBlockedDomains)) return true
            if (useFullAdList && isHostBlockedBySet(lowerHost, fullListCache)) return true
        }

        if (trackingLevel != TrackerBlocker.OFF) {
            val loadQualifies = trackingLevel == TrackerBlocker.STRICT || isThirdParty
            if (loadQualifies && isHostBlockedBySet(lowerHost, TrackerBlocker.trackerDomains)) return true
        }

        return false
    }

    /**
     * Empty response used to swallow a blocked request. Returning this (rather
     * than null) from `shouldInterceptRequest` is what actually stops the
     * resource from loading.
     */
    fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
}

/**
 * Tracker blocking with Edge-style protection levels. Direct port of
 * TrackerBlocker.swift; levels are plain Int constants (rather than a Kotlin enum)
 * since [AdBlocker.shouldBlock]'s `trackingLevel` parameter — and the persisted
 * settings value in BrowserViewModel — just need a simple ordinal to store and
 * compare. Values match TrackerBlocker.Level's Swift rawValue ordering
 * (off=0, balanced=1, strict=2) so the two stay trivially in sync if this is ever
 * shared/serialized alongside iOS state.
 */
object TrackerBlocker {

    const val OFF = 0
    const val BALANCED = 1
    const val STRICT = 2

    /** English-only for now — Android has no bilingual `loc()` helper yet (phase 1 is JA-only in Toolbar.kt etc.). */
    fun label(level: Int): String = when (level) {
        OFF -> "Off"
        BALANCED -> "Balanced"
        STRICT -> "Strict"
        else -> "Off"
    }

    /** Analytics / fingerprinting / social tracking networks. */
    private val trackerDomainPatterns = listOf(
        "google-analytics\\.com",
        "googletagmanager\\.com",
        "connect\\.facebook\\.net",
        "graph\\.facebook\\.com",
        "analytics\\.twitter\\.com",
        "static\\.ads-twitter\\.com",
        "scorecardresearch\\.com",
        "quantserve\\.com",
        "hotjar\\.com",
        "mixpanel\\.com",
        "segment\\.io",
        "segment\\.com",
        "amplitude\\.com",
        "fullstory\\.com",
        "mouseflow\\.com",
        "clarity\\.ms",
        "newrelic\\.com",
        "nr-data\\.net",
        "branch\\.io",
        "appsflyer\\.com",
        "adjust\\.com",
        "chartbeat\\.com",
        "parsely\\.com",
        "crazyegg\\.com",
        "optimizely\\.com",
        "demdex\\.net",
        "omtrdc\\.net",
        "krxd\\.net",
        "bluekai\\.com",
        "mathtag\\.com",
        "everesttech\\.net",
        "uncn\\.jp",
        "ptengine\\.jp",
        "userinsight\\.jp",
        "karte\\.io",
    )

    /** Plain-domain form of [trackerDomainPatterns], built once. Public (Kotlin's default
     *  visibility) so [AdBlocker.shouldBlock] can read it directly. */
    val trackerDomains: Set<String> by lazy {
        trackerDomainPatterns.map { unescapeDomainPattern(it) }.toHashSet()
    }
}
