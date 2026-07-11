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

    /// Safari content-blocker JSON.
    static var rulesJSON: String {
        var rules: [[String: Any]] = blockedDomains.map { domain in
            [
                "trigger": [
                    "url-filter": "^https?://([^/]+\\.)?\(domain)",
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
        let store = WKContentRuleListStore.default()
        if let cached = try? await store?.contentRuleList(forIdentifier: identifier),
           let cached {
            return cached
        }
        let compiled = try? await store?.compileContentRuleList(
            forIdentifier: identifier,
            encodedContentRuleList: rulesJSON
        )
        return compiled ?? nil   // flatten the double optional
    }
}
