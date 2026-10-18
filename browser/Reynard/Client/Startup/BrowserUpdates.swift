//
//  BrowserUpdates.swift
//  Reynard
//
//  Created by Minh Ton on 21/4/26.
//

import Foundation

final class BrowserUpdates: NSObject {
    static let shared = BrowserUpdates()
    
    private(set) var hasUpdate: Bool = false
    private(set) var latestVersion: String = ""
    private(set) var sourceData: Data?
    var cachedReleaseNotes: NSAttributedString?
    
    private static let sourceURL = "https://github.com/soum91/reynard-browser/releases/download/nightly/source.json" // Firefox Sync fork: offer this fork's builds, not upstream's
    private override init() {
        super.init()
        
        guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else { return }
        let sideloadIPA = docs.appendingPathComponent("Reynard.ipa")
        try? FileManager.default.removeItem(at: sideloadIPA)
        
        fetchUpdates()
    }
    
    private func fetchUpdates() {
        DispatchQueue.global(qos: .background).async {
            guard let url = URL(string: Self.sourceURL),
                  let data = try? Data(contentsOf: url) else { return }
            
            self.sourceData = data
            
            guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
                  let apps = json["apps"] as? [[String: Any]],
                  let firstApp = apps.first,
                  let versions = firstApp["versions"] as? [[String: Any]],
                  let latestEntry = versions.first,
                  let latestVersionStr = latestEntry["version"] as? String,
                  let latestDateStr = latestEntry["date"] as? String else { return }
            
            let currentVersionStr = (Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "0.0.0").replacingOccurrences(of: "-dev", with: "")
            let latestBaseVersion = latestVersionStr.replacingOccurrences(of: "-dev", with: "")
            
            // The fork publishes nightlies that keep the same version and differ
            // only by build (the commit), so a different build counts as an update.
            let currentBuild = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? ""
            let latestBuild = latestEntry["buildVersion"] as? String ?? ""
            let isNewerBuildOfSameVersion = !Self.isVersion(currentVersionStr, greaterThan: latestBaseVersion)
                && !Self.isVersion(latestBaseVersion, greaterThan: currentVersionStr)
                && !latestBuild.isEmpty && latestBuild != currentBuild
            
            let versionIsNewer = Self.isVersion(latestBaseVersion, greaterThan: currentVersionStr)
            guard versionIsNewer || isNewerBuildOfSameVersion else { return }
            
            let formatter = ISO8601DateFormatter()
            guard let latestDate = formatter.date(from: latestDateStr) else { return }
            
            if versionIsNewer,
               let currentEntry = versions.first(where: { ($0["version"] as? String) == currentVersionStr }),
               let currentDateStr = currentEntry["date"] as? String,
               let currentDate = formatter.date(from: currentDateStr) {
                guard currentDate < latestDate else { return }
            }
            
            DispatchQueue.main.async {
                self.hasUpdate = true
                self.latestVersion = latestVersionStr
                NotificationCenter.default.post(name: .appUpdateAvailable, object: nil)
            }
        }
    }
    
    private static func isVersion(_ v1: String, greaterThan v2: String) -> Bool {
        let parts1 = v1.split(separator: ".").compactMap { Int($0) }
        let parts2 = v2.split(separator: ".").compactMap { Int($0) }
        
        let maxCount = max(parts1.count, parts2.count)
        for i in 0..<maxCount {
            let p1 = i < parts1.count ? parts1[i] : 0
            let p2 = i < parts2.count ? parts2[i] : 0
            if p1 > p2 { return true }
            if p1 < p2 { return false }
        }
        return false
    }
}
