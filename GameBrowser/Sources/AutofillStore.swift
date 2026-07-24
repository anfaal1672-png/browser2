import Foundation
import Security

/// Credentials and payment cards, stored encrypted in the iOS Keychain.
struct Credential: Codable, Identifiable, Equatable {
    var id = UUID()
    var domain: String
    var username: String
    var password: String
}

struct PaymentCard: Codable, Equatable {
    var number: String = ""
    var holder: String = ""
    var expMonth: String = ""
    var expYear: String = ""

    var isEmpty: Bool { number.isEmpty }
    var maskedNumber: String {
        let digits = number.filter(\.isNumber)
        guard digits.count >= 4 else { return number }
        return "•••• " + digits.suffix(4)
    }
}

enum AutofillStore {

    private static let service = "com.anfaal.gamebrowser.autofill"

    // MARK: - Keychain primitives

    private static func save(_ data: Data, account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(attributes as CFDictionary, nil)
    }

    private static func load(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        SecItemCopyMatching(query as CFDictionary, &result)
        return result as? Data
    }

    // MARK: - Credentials

    static func loadCredentials() -> [Credential] {
        guard let data = load(account: "credentials"),
              let list = try? JSONDecoder().decode([Credential].self, from: data)
        else { return [] }
        return list
    }

    static func saveCredentials(_ credentials: [Credential]) {
        if let data = try? JSONEncoder().encode(credentials) {
            save(data, account: "credentials")
        }
    }

    // MARK: - Payment card

    static func loadCard() -> PaymentCard {
        guard let data = load(account: "card"),
              let card = try? JSONDecoder().decode(PaymentCard.self, from: data)
        else { return PaymentCard() }
        return card
    }

    static func saveCard(_ card: PaymentCard) {
        if let data = try? JSONEncoder().encode(card) {
            save(data, account: "card")
        }
    }

    /// Match saved credentials to a host ("play.example.com" matches
    /// entries saved for "example.com" and vice versa). Suffix matching is
    /// boundary-checked on "." so "evilexample.com" can't match a saved
    /// "example.com" entry just because it happens to share a substring.
    static func credentials(for host: String, in list: [Credential]) -> [Credential] {
        list.filter {
            host == $0.domain
                || host.hasSuffix("." + $0.domain)
                || $0.domain.hasSuffix("." + host)
        }
    }
}
