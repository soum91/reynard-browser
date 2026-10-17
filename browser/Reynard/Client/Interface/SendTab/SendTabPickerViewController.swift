//
//  SendTabPickerViewController.swift
//  Reynard
//
//  Lists the other devices on the Mozilla account that can receive tabs and
//  sends the current page to the one the user picks.
//

import UIKit

@MainActor
final class SendTabPickerViewController: UITableViewController {
    private let url: URL
    private let pageTitle: String?
    private let onFinish: (Bool) -> Void

    private var devices: [FxADevice] = []
    private var isSending = false
    private let spinner = UIActivityIndicatorView(style: .medium)
    private let messageLabel = UILabel()

    init(url: URL, title: String?, onFinish: @escaping (Bool) -> Void) {
        self.url = url
        self.pageTitle = title
        self.onFinish = onFinish
        super.init(style: .insetGrouped)
        self.title = NSLocalizedString("Send Tab", comment: "")
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "device")

        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .cancel,
            target: self,
            action: #selector(cancelTapped)
        )

        messageLabel.numberOfLines = 0
        messageLabel.textAlignment = .center
        messageLabel.textColor = .secondaryLabel
        messageLabel.font = .preferredFont(forTextStyle: .subheadline)

        loadDevices()
    }

    // MARK: - Loading

    private func loadDevices() {
        spinner.startAnimating()
        tableView.backgroundView = spinner
        Task { [weak self] in
            var message: String?
            var found: [FxADevice] = []
            do {
                found = try await FxASendTab.shared.availableDevices()
                if found.isEmpty {
                    message = NSLocalizedString(
                        "No other devices can receive tabs yet. Sign in to Firefox on another device, then try again.",
                        comment: ""
                    )
                }
            } catch {
                message = error.localizedDescription
            }
            guard let self else {
                return
            }
            self.devices = found
            self.spinner.stopAnimating()
            if let message {
                self.messageLabel.text = message
                self.tableView.backgroundView = self.messageLabel
            } else {
                self.tableView.backgroundView = nil
            }
            self.tableView.reloadData()
        }
    }

    // MARK: - Table

    override func numberOfSections(in tableView: UITableView) -> Int {
        1
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        devices.count
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        devices.isEmpty ? nil : NSLocalizedString("Send to", comment: "")
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: "device", for: indexPath)
        let device = devices[indexPath.row]
        cell.textLabel?.text = device.name
        cell.imageView?.image = Self.icon(for: device.type)
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        guard !isSending, devices.indices.contains(indexPath.row) else {
            return
        }
        send(to: devices[indexPath.row])
    }

    private static func icon(for type: String) -> UIImage? {
        switch type {
        case "mobile":
            return UIImage(systemName: "iphone")
        case "tablet":
            return UIImage(systemName: "ipad")
        default:
            return UIImage(systemName: "desktopcomputer") ?? UIImage(systemName: "tv")
        }
    }

    // MARK: - Sending

    private func send(to device: FxADevice) {
        isSending = true
        tableView.isUserInteractionEnabled = false
        let progress = UIActivityIndicatorView(style: .medium)
        progress.startAnimating()
        navigationItem.rightBarButtonItem = UIBarButtonItem(customView: progress)

        let url = self.url
        let pageTitle = self.pageTitle
        Task { [weak self] in
            var failure: Error?
            do {
                try await FxASendTab.shared.send(url: url, title: pageTitle, toDeviceID: device.id)
            } catch {
                failure = error
            }
            guard let self else {
                return
            }
            self.isSending = false
            self.tableView.isUserInteractionEnabled = true
            self.navigationItem.rightBarButtonItem = nil

            if let failure {
                self.presentResult(
                    title: NSLocalizedString("Couldn't Send Tab", comment: ""),
                    message: failure.localizedDescription,
                    finishesAfterwards: false
                )
            } else {
                self.presentResult(
                    title: NSLocalizedString("Tab Sent", comment: ""),
                    message: String(
                        format: NSLocalizedString("Sent to %@. It can take a few seconds to show up.", comment: ""),
                        device.name
                    ),
                    finishesAfterwards: true
                )
            }
        }
    }

    private func presentResult(title: String, message: String, finishesAfterwards: Bool) {
        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: NSLocalizedString("OK", comment: ""), style: .default) { [weak self] _ in
            if finishesAfterwards {
                self?.onFinish(true)
            }
        })
        present(alert, animated: true)
    }

    @objc private func cancelTapped() {
        onFinish(false)
    }
}
