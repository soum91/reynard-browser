//
//  FxASendTab.swift
//  Reynard
//
//  Send Tab: sends a page to another device signed in to the same Mozilla
//  account, using Firefox Account device commands (the protocol Firefox for
//  desktop, Android and iOS use).
//
//  A device that can receive tabs advertises the "open-uri" command in its
//  device record. The command data is that device's one-time push public key
//  and auth secret, encrypted with the account's kSync bundle. To send a tab we
//  decrypt that data, encrypt the tab with it (RFC 8291 aes128gcm), and ask the
//  account server to deliver the result to the device.
//

import CryptoKit
import Foundation

nonisolated struct FxADevice {
    let id: String
    let name: String
    let type: String
    let isCurrentDevice: Bool
    let availableCommands: [String: String]

    var canReceiveTabs: Bool {
        availableCommands[FxASendTabProtocol.commandName] != nil
    }
}

nonisolated enum FxASendTabError: Error, LocalizedError {
    case deviceCannotReceiveTabs
    case deviceKeysUnreadable
    case pageTooLarge
    case malformedMessage

    var errorDescription: String? {
        switch self {
        case .malformedMessage:
            return NSLocalizedString("The tab sent to this device couldn't be read.", comment: "")
        case .deviceCannotReceiveTabs:
            return NSLocalizedString("That device can't receive tabs right now. Open Firefox on it and try again.", comment: "")
        case .deviceKeysUnreadable:
            return NSLocalizedString("Couldn't read that device's keys. Open Firefox on it, wait a moment, and try again.", comment: "")
        case .pageTooLarge:
            return NSLocalizedString("This page's address is too long to send.", comment: "")
        }
    }
}

nonisolated enum FxASendTabProtocol {
    static let commandName = "https://identity.mozilla.com/cmd/open-uri"

    /// aes128gcm uses one record of this size; the payload has to fit in it.
    static let recordSize = 4_096
    private static let maxTitleLength = 200

    /// The JSON a receiving device expects inside the encrypted payload.
    static func payload(url: URL, title: String?) throws -> Data {
        var cleanTitle = (title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if cleanTitle.isEmpty {
            cleanTitle = url.host ?? url.absoluteString
        }
        if cleanTitle.count > maxTitleLength {
            cleanTitle = String(cleanTitle.prefix(maxTitleLength))
        }

        let flowID = FxACrypto.hexString(try FxACrypto.randomBytes(6))
        let streamID = FxACrypto.hexString(try FxACrypto.randomBytes(6))
        let object: [String: Any] = [
            "entries": [
                [
                    "title": cleanTitle,
                    "url": url.absoluteString,
                    "private": false,
                ] as [String: Any],
            ],
            "flowID": flowID,
            "streamID": streamID,
        ]
        let data = try JSONSerialization.data(withJSONObject: object)
        // 1 delimiter byte + 16 byte GCM tag must also fit in the record.
        guard data.count + 17 <= recordSize else {
            throw FxASendTabError.pageTooLarge
        }
        return data
    }

    /// HKDF (RFC 5869) with SHA-256 for outputs up to 32 bytes. CryptoKit's own
    /// HKDF type needs iOS 14, and Reynard supports iOS 13.
    private static func hkdf(ikm: SymmetricKey, salt: Data, info: Data, length: Int) -> Data {
        let ikmData: Data = ikm.withUnsafeBytes { Data($0) }
        let prk = HMAC<SHA256>.authenticationCode(for: ikmData, using: SymmetricKey(data: salt))
        var block = info
        block.append(0x01)
        let okm = HMAC<SHA256>.authenticationCode(for: block, using: SymmetricKey(data: Data(prk)))
        return Data(Data(okm).prefix(length))
    }

    /// RFC 8291 (Web Push) aes128gcm encryption of a single record.
    /// Checked against the example in the RFC.
    static func encrypt(plaintext: Data, receiverPublicKey: Data, authSecret: Data) throws -> Data {
        guard receiverPublicKey.count == 65, receiverPublicKey.first == 0x04 else {
            throw FxASendTabError.deviceKeysUnreadable
        }
        guard authSecret.count == 16 else {
            throw FxASendTabError.deviceKeysUnreadable
        }

        let receiverKey = try P256.KeyAgreement.PublicKey(x963Representation: receiverPublicKey)
        let senderKey = P256.KeyAgreement.PrivateKey()
        let senderPublic = senderKey.publicKey.x963Representation
        let shared = try senderKey.sharedSecretFromKeyAgreement(with: receiverKey)

        var keyInfo = Data("WebPush: info".utf8)
        keyInfo.append(0)
        keyInfo.append(receiverPublicKey)
        keyInfo.append(senderPublic)
        let ikm = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: authSecret,
            sharedInfo: keyInfo,
            outputByteCount: 32
        )

        let salt = try FxACrypto.randomBytes(16)
        var cekInfo = Data("Content-Encoding: aes128gcm".utf8)
        cekInfo.append(0)
        var nonceInfo = Data("Content-Encoding: nonce".utf8)
        nonceInfo.append(0)
        let contentKey = SymmetricKey(data: hkdf(ikm: ikm, salt: salt, info: cekInfo, length: 16))
        let nonceKeyData: Data = hkdf(ikm: ikm, salt: salt, info: nonceInfo, length: 12)
        let nonceBytes: Data = nonceKeyData

        var record = plaintext
        record.append(0x02) // last record delimiter, no extra padding
        let sealed = try AES.GCM.seal(record, using: contentKey, nonce: AES.GCM.Nonce(data: nonceBytes))

        var output = Data()
        output.append(salt)
        output.append(contentsOf: [0x00, 0x00, 0x10, 0x00]) // record size 4096, big endian
        output.append(UInt8(senderPublic.count))
        output.append(senderPublic)
        output.append(sealed.ciphertext)
        output.append(sealed.tag)
        return output
    }

    /// RFC 8291 (Web Push) aes128gcm decryption, for tabs other devices send us.
    /// Checked against the example in the RFC.
    static func decrypt(_ message: Data, receiverPrivateKey: P256.KeyAgreement.PrivateKey, authSecret: Data) throws -> Data {
        // Header: salt (16) | record size (4) | key id length (1) | key id (the sender's public key).
        let bytes = [UInt8](message)
        guard bytes.count > 21, bytes.count <= 64 * 1_024 else {
            throw FxASendTabError.malformedMessage
        }
        let salt = Data(bytes[0..<16])
        var recordSize = 0
        for index in 16..<20 {
            recordSize = (recordSize << 8) | Int(bytes[index])
        }
        let keyIDLength = Int(bytes[20])
        guard keyIDLength == 65, bytes.count > 21 + keyIDLength, recordSize > 17, authSecret.count == 16 else {
            throw FxASendTabError.malformedMessage
        }
        let senderPublic = Data(bytes[21..<(21 + keyIDLength)])
        let body = Array(bytes[(21 + keyIDLength)...])

        let senderKey = try P256.KeyAgreement.PublicKey(x963Representation: senderPublic)
        let shared = try receiverPrivateKey.sharedSecretFromKeyAgreement(with: senderKey)
        let receiverPublic = receiverPrivateKey.publicKey.x963Representation

        var keyInfo = Data("WebPush: info".utf8)
        keyInfo.append(0)
        keyInfo.append(receiverPublic)
        keyInfo.append(senderPublic)
        let ikm = shared.hkdfDerivedSymmetricKey(
            using: SHA256.self,
            salt: authSecret,
            sharedInfo: keyInfo,
            outputByteCount: 32
        )

        var cekInfo = Data("Content-Encoding: aes128gcm".utf8)
        cekInfo.append(0)
        var nonceInfo = Data("Content-Encoding: nonce".utf8)
        nonceInfo.append(0)
        let contentKey = SymmetricKey(data: hkdf(ikm: ikm, salt: salt, info: cekInfo, length: 16))
        let nonceKeyData: Data = hkdf(ikm: ikm, salt: salt, info: nonceInfo, length: 12)
        let baseNonceData: Data = nonceKeyData
        let baseNonce: [UInt8] = Array(baseNonceData)

        var output = Data()
        var offset = 0
        var sequence: UInt64 = 0
        while offset < body.count {
            let end = min(offset + recordSize, body.count)
            let record = Array(body[offset..<end])
            guard record.count > 16 else {
                throw FxASendTabError.malformedMessage
            }
            let isLastRecord = end == body.count

            // Each record's nonce is the base nonce XOR the record number.
            var nonce: [UInt8] = baseNonce
            for position in 0..<8 {
                let shift: UInt64 = UInt64(8 * position)
                let sequenceByte: UInt8 = UInt8(truncatingIfNeeded: sequence >> shift)
                nonce[11 - position] = nonce[11 - position] ^ sequenceByte
            }
            let recordNonce = try AES.GCM.Nonce(data: Data(nonce))
            let cipherLength = record.count - 16
            let cipherBytes = Data(record[0..<cipherLength])
            let tagBytes = Data(record[cipherLength..<record.count])
            let sealedBox = try AES.GCM.SealedBox(nonce: recordNonce, ciphertext: cipherBytes, tag: tagBytes)
            let opened: Data = try AES.GCM.open(sealedBox, using: contentKey)
            let plain: [UInt8] = Array(opened)

            // Strip padding: zeros, then a 0x01 delimiter (0x02 on the last record).
            let expectedDelimiter: UInt8 = isLastRecord ? 2 : 1
            var delimiter = plain.count - 1
            while delimiter >= 0 && plain[delimiter] == 0 {
                delimiter -= 1
            }
            guard delimiter >= 0 else {
                throw FxASendTabError.malformedMessage
            }
            guard plain[delimiter] == expectedDelimiter else {
                throw FxASendTabError.malformedMessage
            }
            output.append(contentsOf: plain[0..<delimiter])

            sequence += 1
            offset = end
        }
        return output
    }
}

final class FxASendTab {
    static let shared = FxASendTab()

    private var knownDevices: [String: FxADevice] = [:]

    private init() {}

    /// Other devices on the account that can receive tabs.
    func availableDevices() async throws -> [FxADevice] {
        let all = try await fetchDevices()
        let currentID = FxAAccountManager.shared.account?.deviceID
        return all.filter { $0.canReceiveTabs && !$0.isCurrentDevice && $0.id != currentID }
    }

    /// Name of one of the account's devices, for "tab received from ...".
    func deviceName(for deviceID: String) async -> String? {
        if knownDevices[deviceID] == nil {
            _ = try? await fetchDevices()
        }
        return knownDevices[deviceID]?.name
    }

    func send(url: URL, title: String?, toDeviceID deviceID: String) async throws {
        guard let account = FxAAccountManager.shared.account else {
            throw FxAAccountError.notSignedIn
        }

        var lookup = knownDevices[deviceID]
        if lookup == nil {
            _ = try await fetchDevices()
            lookup = knownDevices[deviceID]
        }
        guard let target = lookup,
              let commandData = target.availableCommands[FxASendTabProtocol.commandName] else {
            throw FxASendTabError.deviceCannotReceiveTabs
        }

        let keys: [String: Any]
        do {
            keys = try FxAAccountManager.shared.syncKeyBundle().decrypt(payload: commandData)
        } catch {
            FxADebugLog.shared.log("send tab: could not read device keys (\(error.localizedDescription))")
            throw FxASendTabError.deviceKeysUnreadable
        }
        guard let publicKeyString = keys["publicKey"] as? String,
              let authSecretString = keys["authSecret"] as? String,
              let publicKey = Base64URL.decode(publicKeyString),
              let authSecret = Base64URL.decode(authSecretString) else {
            throw FxASendTabError.deviceKeysUnreadable
        }

        let plaintext = try FxASendTabProtocol.payload(url: url, title: title)
        let encrypted = try FxASendTabProtocol.encrypt(
            plaintext: plaintext,
            receiverPublicKey: publicKey,
            authSecret: authSecret
        )

        let config = await FxAConfig.discover()
        let response = try await FxAHTTP.send(FxAHTTP.makeRequest(
            url: config.authServerURL(path: "account/devices/invoke_command"),
            method: "POST",
            json: [
                "command": FxASendTabProtocol.commandName,
                "target": deviceID,
                "payload": ["encrypted": Base64URL.encode(encrypted)],
            ] as [String: Any],
            headers: ["Authorization": "Bearer \(account.refreshToken)"]
        ))
        try check(response, action: "send tab")
        FxADebugLog.shared.log("send tab: delivered to \(target.type) device")
    }

    // MARK: - Helpers

    private func fetchDevices() async throws -> [FxADevice] {
        guard let account = FxAAccountManager.shared.account else {
            throw FxAAccountError.notSignedIn
        }

        let config = await FxAConfig.discover()
        var components = URLComponents(url: config.authServerURL(path: "account/devices"), resolvingAgainstBaseURL: false)
        // Hide devices that haven't been seen for three weeks, like Firefox does.
        let cutoff = Int((Date().timeIntervalSince1970 - 21 * 24 * 60 * 60) * 1_000)
        components?.queryItems = [URLQueryItem(name: "filterIdleDevicesTimestamp", value: String(cutoff))]
        guard let url = components?.url else {
            throw FxAHTTPError.invalidResponse("Could not build the devices URL")
        }

        let response = try await FxAHTTP.send(FxAHTTP.makeRequest(
            url: url,
            headers: ["Authorization": "Bearer \(account.refreshToken)"]
        ))
        try check(response, action: "list devices")
        guard let array = response.jsonArray() else {
            throw FxAHTTPError.invalidResponse("Expected a device list from the account server")
        }

        let devices: [FxADevice] = array.compactMap { entry in
            guard let id = entry["id"] as? String else {
                return nil
            }
            return FxADevice(
                id: id,
                name: (entry["name"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? NSLocalizedString("Unnamed device", comment: ""),
                type: entry["type"] as? String ?? "desktop",
                isCurrentDevice: entry["isCurrentDevice"] as? Bool ?? false,
                availableCommands: entry["availableCommands"] as? [String: String] ?? [:]
            )
        }
        knownDevices = Dictionary(devices.map { ($0.id, $0) }, uniquingKeysWith: { _, latest in latest })
        FxADebugLog.shared.log("send tab: \(devices.count) device(s), \(devices.filter(\.canReceiveTabs).count) can receive tabs")
        return devices
    }

    private func check(_ response: FxAHTTPResponse, action: String) throws {
        guard !response.isSuccess else {
            return
        }
        FxADebugLog.shared.log("\(action) failed: HTTP \(response.status) \(response.bodyText.prefix(200))")
        if response.status == 401 {
            FxAAccountManager.shared.markNeedsReauthentication()
            throw FxAAccountError.reauthenticationRequired
        }
        throw FxAHTTPError.unexpectedStatus(response.status, response.bodyText)
    }
}
