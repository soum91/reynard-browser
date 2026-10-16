//
//  FxAReceiveTab.swift
//  Reynard
//
//  Receive Tab: other devices on the account can send pages to Reynard.
//
//  Reynard advertises the "open-uri" command in its device record, together
//  with a one-time push public key and auth secret wrapped by kSync (see
//  FxASendTab.swift for the other direction). Sideloaded apps can't receive
//  push notifications, so incoming tabs are fetched by polling the account
//  server while the app is open.
//

import CryptoKit
import Foundation

struct FxAReceivedTab {
    let title: String
    let url: URL
    let senderName: String?
}

/// The key pair other devices encrypt tabs to, kept in the account state.
enum FxAReceiveKeys {
    /// Creates the keys if this account doesn't have them yet. Returns true when it did.
    static func ensure(in state: inout FxAAccountState) throws -> Bool {
        if load(from: state) != nil {
            return false
        }
        let privateKey = P256.KeyAgreement.PrivateKey()
        let authSecret = try FxACrypto.randomBytes(16)
        state.commandKeysPrivate = Base64URL.encode(privateKey.rawRepresentation)
        state.commandKeysAuthSecret = Base64URL.encode(authSecret)
        state.commandsRegisteredKID = nil
        return true
    }

    static func load(from state: FxAAccountState) -> (privateKey: P256.KeyAgreement.PrivateKey, authSecret: Data)? {
        guard let privateString = state.commandKeysPrivate,
              let authString = state.commandKeysAuthSecret,
              let privateData = Base64URL.decode(privateString),
              let authSecret = Base64URL.decode(authString),
              authSecret.count == 16,
              let privateKey = try? P256.KeyAgreement.PrivateKey(rawRepresentation: privateData) else {
            return nil
        }
        return (privateKey, authSecret)
    }

    /// The `availableCommands` value for open-uri: our public key and auth
    /// secret, encrypted with kSync so only the account's own devices read it.
    static func commandData(for state: FxAAccountState, bundle: SyncKeyBundle) throws -> String {
        guard let keys = load(from: state) else {
            throw FxACryptoError.invalidInput("Receive Tab keys are missing")
        }

        let parts = state.syncKey.kid.split(separator: "-", maxSplits: 1).map(String.init)
        guard parts.count == 2, let keyRevision = Base64URL.decode(parts[1]) else {
            throw FxACryptoError.invalidInput("Unexpected Sync key id")
        }

        let publicKeys: [String: Any] = [
            "publicKey": Base64URL.encode(keys.privateKey.publicKey.x963Representation),
            "authSecret": Base64URL.encode(keys.authSecret),
        ]
        let encrypted = try bundle.encrypt(publicKeys)
        guard let envelopeData = encrypted.data(using: .utf8),
              var envelope = try JSONSerialization.jsonObject(with: envelopeData) as? [String: Any] else {
            throw FxACryptoError.invalidInput("Could not wrap the Receive Tab keys")
        }
        envelope["kid"] = FxACrypto.hexString(keyRevision)
        let data = try JSONSerialization.data(withJSONObject: envelope)
        return String(decoding: data, as: UTF8.self)
    }
}

final class FxAReceiveTab {
    static let shared = FxAReceiveTab()

    /// Called on the main actor with tabs that just arrived.
    var onTabsReceived: (([FxAReceivedTab]) -> Void)?

    private var isPolling = false
    private var pollingTask: Task<Void, Never>?

    private init() {}

    // MARK: - Scheduling

    /// Checks right away, then once a minute until `stopPolling()`.
    func startPolling() {
        guard pollingTask == nil else {
            return
        }
        pollingTask = Task { [weak self] in
            while !Task.isCancelled {
                await self?.poll()
                try? await Task.sleep(nanoseconds: 60 * 1_000_000_000)
            }
        }
    }

    func stopPolling() {
        pollingTask?.cancel()
        pollingTask = nil
    }

    // MARK: - Polling

    func poll() async {
        let accounts = FxAAccountManager.shared
        guard accounts.isSignedIn, !accounts.needsReauthentication, !isPolling else {
            return
        }
        isPolling = true
        defer { isPolling = false }

        await accounts.registerDeviceIfNeeded()
        guard let account = accounts.account,
              let keys = FxAReceiveKeys.load(from: account),
              account.commandsRegisteredKID == account.syncKey.kid else {
            return
        }

        let config = await FxAConfig.discover()
        var components = URLComponents(url: config.authServerURL(path: "account/device/commands"), resolvingAgainstBaseURL: false)
        // The server answers with commands from this index on.
        let nextIndex = (account.lastHandledCommandIndex ?? 0) + 1
        components?.queryItems = [URLQueryItem(name: "index", value: String(nextIndex))]
        guard let url = components?.url else {
            return
        }

        do {
            let response = try await FxAHTTP.send(FxAHTTP.makeRequest(
                url: url,
                headers: ["Authorization": "Bearer \(account.refreshToken)"]
            ))
            if response.status == 401 {
                accounts.markNeedsReauthentication()
                return
            }
            guard response.isSuccess, let json = response.jsonObject() else {
                FxADebugLog.shared.log("receive tab: checking for tabs failed (HTTP \(response.status))")
                return
            }

            let messages = json["messages"] as? [[String: Any]] ?? []
            guard !messages.isEmpty else {
                return
            }

            var received: [FxAReceivedTab] = []
            var highestIndex = account.lastHandledCommandIndex ?? 0
            for message in messages {
                if let index = (message["index"] as? NSNumber)?.intValue {
                    highestIndex = max(highestIndex, index)
                }
                guard let data = message["data"] as? [String: Any],
                      data["command"] as? String == FxASendTabProtocol.commandName,
                      let payload = data["payload"] as? [String: Any],
                      let encrypted = payload["encrypted"] as? String else {
                    continue
                }

                do {
                    let tabs = try Self.tabs(fromEncrypted: encrypted, keys: keys)
                    let senderName: String?
                    if let senderID = data["sender"] as? String {
                        senderName = await FxASendTab.shared.deviceName(for: senderID)
                    } else {
                        senderName = nil
                    }
                    received.append(contentsOf: tabs.map {
                        FxAReceivedTab(title: $0.title, url: $0.url, senderName: senderName)
                    })
                } catch {
                    FxADebugLog.shared.log("receive tab: couldn't read a tab (\(error.localizedDescription))")
                }
            }

            if let index = (json["index"] as? NSNumber)?.intValue {
                highestIndex = max(highestIndex, index)
            }
            // Remember how far we got, even if some messages couldn't be read.
            accounts.updateAccount { $0.lastHandledCommandIndex = highestIndex }

            FxADebugLog.shared.log("receive tab: \(received.count) tab(s) received")
            if !received.isEmpty {
                onTabsReceived?(received)
            }
        } catch {
            FxADebugLog.shared.log("receive tab: \(error.localizedDescription)")
        }
    }

    // MARK: - Decoding

    private static func tabs(
        fromEncrypted encrypted: String,
        keys: (privateKey: P256.KeyAgreement.PrivateKey, authSecret: Data)
    ) throws -> [(title: String, url: URL)] {
        guard let blob = Base64URL.decode(encrypted) else {
            throw FxASendTabError.malformedMessage
        }
        let plaintext = try FxASendTabProtocol.decrypt(
            blob,
            receiverPrivateKey: keys.privateKey,
            authSecret: keys.authSecret
        )
        guard let object = try JSONSerialization.jsonObject(with: plaintext) as? [String: Any],
              let entries = object["entries"] as? [[String: Any]] else {
            throw FxASendTabError.malformedMessage
        }

        var tabs: [(title: String, url: URL)] = []
        for entry in entries {
            // Only web pages: never act on javascript:, file: or other schemes from a message.
            guard let urlString = entry["url"] as? String,
                  let url = URL(string: urlString),
                  let scheme = url.scheme?.lowercased(),
                  scheme == "http" || scheme == "https" else {
                continue
            }
            let title = (entry["title"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? url.host ?? urlString
            tabs.append((title, url))
        }
        return tabs
    }
}
