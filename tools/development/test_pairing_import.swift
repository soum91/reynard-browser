import Foundation
import CryptoKit

func require(_ condition: Bool, _ message: String) {
    if !condition { fatalError(message) }
}
let root = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
defer { try? FileManager.default.removeItem(at: root) }
let key = Curve25519.Signing.PrivateKey()
let valid: [String: Any] = ["identifier": "fixture", "public_key": key.publicKey.rawRepresentation, "private_key": key.rawRepresentation]
let source = root.appendingPathComponent("source.plist")
let destination = root.appendingPathComponent("installed.plist")
let original = try PropertyListSerialization.data(fromPropertyList: valid, format: .xml, options: 0)
try original.write(to: destination)
let invalid: [[String: Any]] = [
    ["HostID": "fixture", "RootCertificate": Data([1])],
    ["identifier": "fixture", "public_key": Data([1]), "private_key": key.rawRepresentation],
    ["identifier": "fixture", "public_key": Curve25519.Signing.PrivateKey().publicKey.rawRepresentation, "private_key": key.rawRepresentation],
    ["identifier": "", "public_key": key.publicKey.rawRepresentation, "private_key": key.rawRepresentation]
]
for value in invalid {
    try PropertyListSerialization.data(fromPropertyList: value, format: .xml, options: 0).write(to: source)
    do { try PairingFileInstaller.install(from: source, to: destination); fatalError("Invalid import succeeded") }
    catch is PairingFileImportError { }
    require(try Data(contentsOf: destination) == original, "Invalid import replaced previous pairing")
}
for format in [PropertyListSerialization.PropertyListFormat.xml, .binary] {
    let data = try PropertyListSerialization.data(fromPropertyList: valid, format: format, options: 0)
    try data.write(to: source)
    try PairingFileInstaller.install(from: source, to: destination)
    require(try Data(contentsOf: destination) == data, "Valid pairing did not install")
}
print("Pairing import validation and atomic replacement passed")
