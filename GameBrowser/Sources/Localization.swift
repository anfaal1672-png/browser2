import Foundation

/// Minimal in-app localization (Japanese / English). Strings are picked at
/// call time so switching the language re-renders immediately — no restart.
enum L {
    /// 0 = system, 1 = 日本語, 2 = English
    static var setting = UserDefaults.standard.integer(forKey: "appLanguage")

    static var isJapanese: Bool {
        switch setting {
        case 1: return true
        case 2: return false
        default: return Locale.preferredLanguages.first?.hasPrefix("ja") ?? false
        }
    }

    /// Target language for the page-translation feature.
    static var translationTarget: String { isJapanese ? "ja" : "en" }
}

/// Pick the string matching the current app language.
func loc(_ ja: String, _ en: String) -> String { L.isJapanese ? ja : en }
