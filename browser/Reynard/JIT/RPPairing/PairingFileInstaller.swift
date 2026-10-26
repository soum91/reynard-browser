import Foundation
import CryptoKit

enum PairingFileImportError: LocalizedError {
    case lockdownFile
    case invalidRemotePairingFile

    var errorDescription: String? {
        switch self {
        case .lockdownFile:
            return "This is a Lockdown pairing file. Reynard needs a Remote Pairing (RPPairing) file. Generate a remote pairing file for this device using idevice_pair, then import it here."
        case .invalidRemotePairingFile:
            return "This is not a valid Remote Pairing (RPPairing) file. Generate a new remote pairing file for this device using idevice_pair."
        }
    }
}

enum PairingFileInstaller {
    static func validate(_ data: Data) throws {
        guard let plist = try? PropertyListSerialization.propertyList(from: data, options: [], format: nil),
              let values = plist as? [String: Any] else {
            throw PairingFileImportError.invalidRemotePairingFile
        }
        guard let publicKey = values["public_key"] as? Data, publicKey.count == 32,
              let privateKey = values["private_key"] as? Data, privateKey.count == 32,
              let identifier = values["identifier"] as? String,
              !identifier.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            if values["HostID"] != nil || values["RootCertificate"] != nil {
                throw PairingFileImportError.lockdownFile
            }
            throw PairingFileImportError.invalidRemotePairingFile
        }
        guard let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: privateKey),
              key.publicKey.rawRepresentation == publicKey else {
            throw PairingFileImportError.invalidRemotePairingFile
        }
    }

    static func install(from source: URL, to destination: URL) throws {
        let scoped = source.startAccessingSecurityScopedResource()
        defer { if scoped { source.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: source)
        try validate(data)
        try FileManager.default.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
        // Validate first; a failed read or replacement must preserve the old file.
        #if os(iOS)
        try data.write(to: destination, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try data.write(to: destination, options: .atomic)
        #endif
    }
}
