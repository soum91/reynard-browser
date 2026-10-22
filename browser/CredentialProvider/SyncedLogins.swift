//
//  SyncedLogins.swift
//  Reynard
//
//  Logins downloaded from Firefox Sync, shared between the app (which syncs them)
//  and the AutoFill extension (which offers them to iOS).
//
//  Security rules for this file and everything that uses it:
//  - Logins live only in the Keychain (this device only, after first unlock).
//    There is deliberately no file fallback: if the Keychain is unavailable,
//    passwords are simply not stored.
//  - Never log usernames, passwords or the store contents.
//

import Foundation
import Security

nonisolated struct SyncedLogin: Codable, Equatable, Sendable {
    /// Firefox Sync record id (GUID).
    let id: String
    /// Site origin as Firefox stores it, e.g. "https://accounts.example.com".
    let origin: String
    let username: String
    let password: String
    /// Milliseconds since 1970 when the password last changed, 0 if unknown.
    let changed: Double

    var host: String {
        if let host = URL(string: origin)?.host, !host.isEmpty {
            return host.lowercased()
        }
        return origin.lowercased()
    }

    /// True when this login belongs to `host` or one of its parent or child domains.
    func matches(host other: String) -> Bool {
        let a = Self.strippedHost(host)
        let b = Self.strippedHost(other.lowercased())
        guard !a.isEmpty, !b.isEmpty else {
            return false
        }
        return a == b || a.hasSuffix("." + b) || b.hasSuffix("." + a)
    }

    private static func strippedHost(_ host: String) -> String {
        host.hasPrefix("www.") ? String(host.dropFirst(4)) : host
    }
}

nonisolated enum SyncedLoginStore {
    private static let service = "com.minh-ton.Reynard.SyncedLogins"
    private static let account = "firefox"

    /// Reads all synced logins. The query names no access group, so both the app and the
    /// AutoFill extension find the item in whichever shared group their signature grants.
    static func load() -> [SyncedLogin] {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let logins = try? JSONDecoder().decode([SyncedLogin].self, from: data) else {
            return []
        }
        return logins
    }

    /// Replaces the stored logins. Returns false when the Keychain refused the write.
    @discardableResult
    static func save(_ logins: [SyncedLogin]) -> Bool {
        guard let data = try? JSONEncoder().encode(logins) else {
            return false
        }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        let updateStatus = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if updateStatus == errSecSuccess {
            return true
        }
        guard updateStatus == errSecItemNotFound else {
            NSLog("Reynard Passwords: keychain update failed (%d)", updateStatus)
            return false
        }
        var addQuery = query
        attributes.forEach { addQuery[$0.key] = $0.value }
        let addStatus = SecItemAdd(addQuery as CFDictionary, nil)
        if addStatus != errSecSuccess {
            NSLog("Reynard Passwords: keychain add failed (%d)", addStatus)
        }
        return addStatus == errSecSuccess
    }

    static func clear() {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        SecItemDelete(query as CFDictionary)
    }
}
