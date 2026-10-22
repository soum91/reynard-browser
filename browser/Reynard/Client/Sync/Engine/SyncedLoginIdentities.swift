//
//  SyncedLoginIdentities.swift
//  Reynard
//
//  Tells iOS which accounts the Reynard AutoFill extension can fill, so they show up
//  as QuickType suggestions. Only the site and username are handed to iOS; passwords
//  stay in Reynard's Keychain item and are released by the extension after Face ID.
//

import AuthenticationServices
import Foundation

nonisolated enum SyncedLoginIdentities {
    static func update(with logins: [SyncedLogin]) async {
        let store = ASCredentialIdentityStore.shared
        guard await store.state().isEnabled else {
            return
        }
        let identities = logins
            .filter { !$0.username.isEmpty }
            .map {
                ASPasswordCredentialIdentity(
                    serviceIdentifier: ASCredentialServiceIdentifier(identifier: $0.host, type: .domain),
                    user: $0.username,
                    recordIdentifier: $0.id
                )
            }
        do {
            try await store.replaceCredentialIdentities(with: identities)
        } catch {
            NSLog("Reynard Passwords: updating AutoFill suggestions failed: %@", error.localizedDescription)
        }
    }

    static func removeAll() async {
        do {
            try await ASCredentialIdentityStore.shared.removeAllCredentialIdentities()
        } catch {
            NSLog("Reynard Passwords: clearing AutoFill suggestions failed: %@", error.localizedDescription)
        }
    }
}
