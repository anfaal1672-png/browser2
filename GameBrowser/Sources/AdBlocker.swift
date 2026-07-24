import WebKit

/// Ad blocking via WKContentRuleList — the same engine Safari content
/// blockers use. Requests to ad networks are blocked at the network level
/// and common ad containers are hidden with CSS.
enum AdBlocker {

    static let identifier = "gb-adblock"

    /// Third-party ad / tracking networks blocked outright.
    private static let blockedDomains = [
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
    ]

    /// Common ad container selectors hidden cosmetically.
    private static let hiddenSelectors = [
        "ins.adsbygoogle",
        "[id^='google_ads_']",
        "[id^='div-gpt-ad']",
        "iframe[src*='doubleclick.net']",
        "iframe[src*='googlesyndication.com']",
        ".ad-banner", ".ad-container", ".ad-wrapper", ".ad-placeholder",
        "[class*='taboola']", "[id*='taboola']",
        "[class*='outbrain']", "[id*='outbrain']",
    ]

    /// Builds a url-filter regex matching the domain or any of its
    /// subdomains, anchored right after the domain so an unrelated domain
    /// that merely starts with the same characters (e.g.
    /// "amazon-adsystem.company.com") can't match "amazon-adsystem.com".
    static func domainURLFilter(_ domain: String) -> String {
        "^https?://([^/]+\\.)?\(domain)(?:[:/]|$)"
    }

    /// Safari content-blocker JSON.
    static var rulesJSON: String {
        var rules: [[String: Any]] = blockedDomains.map { domain in
            [
                "trigger": [
                    "url-filter": domainURLFilter(domain),
                    "load-type": ["third-party"],
                ],
                "action": ["type": "block"],
            ]
        }
        rules.append([
            "trigger": ["url-filter": ".*"],
            "action": [
                "type": "css-display-none",
                "selector": hiddenSelectors.joined(separator: ", "),
            ],
        ])
        let data = try? JSONSerialization.data(withJSONObject: rules)
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
    }

    /// Compile (or fetch the cached) rule list.
    static func compiledRuleList() async -> WKContentRuleList? {
        await compile(identifier: identifier, json: rulesJSON)
    }

    static func compile(identifier: String, json: String) async -> WKContentRuleList? {
        let store = WKContentRuleListStore.default()
        let compiled = try? await store?.compileContentRuleList(
            forIdentifier: identifier,
            encodedContentRuleList: json
        )
        return compiled ?? nil   // flatten the double optional
    }

    // MARK: - Full EasyList mode

    /// EasyList converted to Safari content-blocker JSON, published by the
    /// Adblock Plus project (tens of thousands of rules).
    private static let fullListURL =
        URL(string: "https://easylist-downloads.adblockplus.org/easylist_content_blocker.json")!
    static let fullIdentifier = "gb-adblock-full"
    private static let refreshInterval: TimeInterval = 7 * 24 * 3600

    /// Full-strength rule list: reuses WebKit's compiled cache, refreshing the
    /// download weekly. Returns nil on failure (caller falls back to builtin).
    static func fullRuleList() async -> WKContentRuleList? {
        let defaults = UserDefaults.standard
        let lastFetch = defaults.double(forKey: "adblockFullFetchedAt")
        let fresh = Date().timeIntervalSince1970 - lastFetch < refreshInterval

        let store = WKContentRuleListStore.default()
        if fresh,
           let cached = try? await store?.contentRuleList(forIdentifier: fullIdentifier) {
            return cached
        }

        guard let (data, response) = try? await URLSession.shared.data(from: fullListURL),
              (response as? HTTPURLResponse)?.statusCode == 200,
              let json = String(data: data, encoding: .utf8), !json.isEmpty
        else {
            // Offline / gone: any previously compiled copy is still valid.
            let stale = try? await store?.contentRuleList(forIdentifier: fullIdentifier)
            return stale ?? nil
        }

        let compiled = await compile(identifier: fullIdentifier, json: json)
        if compiled != nil {
            defaults.set(Date().timeIntervalSince1970, forKey: "adblockFullFetchedAt")
        }
        return compiled
    }
}

/// Tracker blocking with Edge-style protection levels.
enum TrackerBlocker {

    enum Level: Int, CaseIterable {
        case off, balanced, strict

        var label: String {
            switch self {
            case .off: return loc("オフ", "Off")
            case .balanced: return loc("バランス", "Balanced")
            case .strict: return loc("厳重", "Strict")
            }
        }
    }

    /// Analytics / fingerprinting / social tracking networks.
    private static let trackerDomains = [
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
    ]

    static func identifier(for level: Level) -> String { "gb-tracking-\(level.rawValue)" }

    static func rulesJSON(for level: Level) -> String {
        guard level != .off else { return "[]" }
        // Balanced: block trackers loaded as third-party resources.
        // Strict: block them on any load type (may break some sites).
        let rules: [[String: Any]] = trackerDomains.map { domain in
            var trigger: [String: Any] = [
                "url-filter": AdBlocker.domainURLFilter(domain),
            ]
            if level == .balanced {
                trigger["load-type"] = ["third-party"]
            }
            return ["trigger": trigger, "action": ["type": "block"]]
        }
        let data = try? JSONSerialization.data(withJSONObject: rules)
        return data.flatMap { String(data: $0, encoding: .utf8) } ?? "[]"
    }

    static func compiledRuleList(for level: Level) async -> WKContentRuleList? {
        guard level != .off else { return nil }
        return await AdBlocker.compile(
            identifier: identifier(for: level),
            json: rulesJSON(for: level)
        )
    }
}
