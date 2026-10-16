//
//  SendTabActivity.swift
//  Reynard
//
//  "Send Tab to Device" entry in the share sheet. Shown only while signed in
//  to a Mozilla account; opens a picker listing the account's other devices.
//

import UIKit

@MainActor
final class SendTabActivity: UIActivity {
    static let identifier = UIActivity.ActivityType("com.minh-ton.Reynard.SendTabActivity")
    private let url: URL
    private let pageTitle: String?

    init(url: URL, title: String?) {
        self.url = url
        self.pageTitle = title
        super.init()
    }

    override class var activityCategory: UIActivity.Category { .action }
    override var activityType: UIActivity.ActivityType? { Self.identifier }
    override var activityTitle: String? { NSLocalizedString("Send Tab to Device", comment: "") }
    override var activityImage: UIImage? { UIImage(systemName: "paperplane") }

    override func canPerform(withActivityItems activityItems: [Any]) -> Bool {
        let accounts = FxAAccountManager.shared
        guard accounts.isSignedIn, !accounts.needsReauthentication else {
            return false
        }
        guard let scheme = url.scheme?.lowercased() else {
            return false
        }
        return scheme == "http" || scheme == "https"
    }

    override var activityViewController: UIViewController? {
        let picker = SendTabPickerViewController(url: url, title: pageTitle) { [weak self] sent in
            self?.activityDidFinish(sent)
        }
        return UINavigationController(rootViewController: picker)
    }
}
