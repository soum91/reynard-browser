import Foundation
import UIKit

/// Explicit, user-initiated handoff to the companion on the same device.
@MainActor
enum FetchDockBridge {
    private static var pending = Set<String>()
    private static var lastOpened: (String, Date)?

    static func handle(uri: String, userInitiated: Bool) async -> Bool {
        guard let components = URLComponents(string: uri), components.scheme?.lowercased() == "fetchdock" else { return false }
        // Invalid or unsolicited FetchDock navigations are consumed, never loaded by Gecko.
        guard userInitiated,
              uri.utf8.count <= 16384,
              components.host == "download", components.path.isEmpty || components.path == "/",
              let values = components.queryItems?.filter({ $0.name == "url" }), values.count == 1,
              let value = values.first?.value, let destination = URL(string: value),
              destination.scheme?.lowercased() == "https", destination.host?.isEmpty == false,
              destination.user == nil, destination.password == nil else { return true }
        if pending.contains(value) { return true }
        if let previous = lastOpened, previous.0 == value, Date().timeIntervalSince(previous.1) < 1 { return true }
        var handoff = URLComponents()
        handoff.scheme = "fetchdock"; handoff.host = "download"
        handoff.queryItems = [URLQueryItem(name: "url", value: destination.absoluteString)]
        guard let url = handoff.url else { return true }
        pending.insert(value)
        defer { pending.remove(value) }
        let opened: Bool = await withCheckedContinuation { continuation in
            UIApplication.shared.open(url, options: [:]) { continuation.resume(returning: $0) }
        }
        if opened { lastOpened = (value, Date()) }
        else { AlertPresenter.show(title: "FetchDock", message: "Install FetchDock on this iPhone or iPad to download this file.") }
        return true
    }
}
