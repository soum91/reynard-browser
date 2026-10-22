//
//  CredentialProviderViewController.swift
//  Reynard AutoFill
//
//  Offers passwords synced from Firefox to iOS AutoFill. Every fill asks for
//  Face ID / Touch ID / passcode first; nothing is released without it.
//

import AuthenticationServices
import LocalAuthentication
import UIKit

final class CredentialProviderViewController: ASCredentialProviderViewController {
    private var listController: LoginListViewController?
    private var pendingIdentity: ASPasswordCredentialIdentity?
    private var pendingServices: [ASCredentialServiceIdentifier] = []

    // MARK: - ASCredentialProviderViewController

    /// QuickType suggestion tapped: always go through the UI so the user authenticates.
    override func provideCredentialWithoutUserInteraction(for credentialIdentity: ASPasswordCredentialIdentity) {
        cancel(.userInteractionRequired)
    }

    override func prepareInterfaceToProvideCredential(for credentialIdentity: ASPasswordCredentialIdentity) {
        pendingIdentity = credentialIdentity
    }

    /// Key icon / "Passwords…" picker: show the list for the requesting site.
    override func prepareCredentialList(for serviceIdentifiers: [ASCredentialServiceIdentifier]) {
        pendingServices = serviceIdentifiers
    }

    override func prepareInterfaceForExtensionConfiguration() {
        let label = UILabel()
        label.numberOfLines = 0
        label.textAlignment = .center
        label.text = NSLocalizedString("Reynard fills passwords from your Firefox account. Turn on Passwords in Reynard › Settings › Firefox Account and sync.", comment: "")
        showMessage(label, doneTitle: NSLocalizedString("Done", comment: "")) { [weak self] in
            self?.extensionContext.completeExtensionConfigurationRequest()
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard listController == nil, presentedViewController == nil, children.isEmpty else {
            return
        }
        authenticate { [weak self] in
            self?.showCredentials()
        }
    }

    // MARK: - Flow

    private func authenticate(then work: @escaping () -> Void) {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            // No passcode set on the device: refuse rather than hand out passwords unprotected.
            cancel(.failed)
            return
        }
        context.evaluatePolicy(
            .deviceOwnerAuthentication,
            localizedReason: NSLocalizedString("Fill a password saved in Firefox", comment: "")
        ) { success, _ in
            DispatchQueue.main.async {
                if success {
                    work()
                } else {
                    self.cancel(.userCanceled)
                }
            }
        }
    }

    private func showCredentials() {
        let logins = SyncedLoginStore.load()

        if let identity = pendingIdentity {
            if let login = logins.first(where: { $0.id == identity.recordIdentifier }) {
                complete(with: login)
            } else {
                cancel(.credentialIdentityNotFound)
            }
            return
        }

        let hosts = pendingServices.compactMap { Self.host(of: $0) }
        let list = LoginListViewController(logins: logins, hosts: hosts)
        list.onSelect = { [weak self] login in
            self?.complete(with: login)
        }
        list.onCancel = { [weak self] in
            self?.cancel(.userCanceled)
        }
        listController = list
        embed(UINavigationController(rootViewController: list))
    }

    private func complete(with login: SyncedLogin) {
        let credential = ASPasswordCredential(user: login.username, password: login.password)
        extensionContext.completeRequest(withSelectedCredential: credential, completionHandler: nil)
    }

    private func cancel(_ code: ASExtensionError.Code) {
        extensionContext.cancelRequest(withError: NSError(domain: ASExtensionErrorDomain, code: code.rawValue))
    }

    // MARK: - Helpers

    private static func host(of service: ASCredentialServiceIdentifier) -> String? {
        switch service.type {
        case .URL:
            return URL(string: service.identifier)?.host?.lowercased()
        case .domain:
            return service.identifier.lowercased()
        @unknown default:
            return nil
        }
    }

    private func embed(_ child: UIViewController) {
        addChild(child)
        child.view.frame = view.bounds
        child.view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        view.addSubview(child.view)
        child.didMove(toParent: self)
    }

    private func showMessage(_ label: UILabel, doneTitle: String, done: @escaping () -> Void) {
        listController = nil
        embed(UINavigationController(rootViewController: MessageViewController(label: label, doneTitle: doneTitle, done: done)))
    }
}

private final class MessageViewController: UIViewController {
    private let label: UILabel
    private let done: () -> Void

    init(label: UILabel, doneTitle: String, done: @escaping () -> Void) {
        self.label = label
        self.done = done
        super.init(nibName: nil, bundle: nil)
        title = "Reynard"
        navigationItem.rightBarButtonItem = UIBarButtonItem(title: doneTitle, style: .done, target: self, action: #selector(doneTapped))
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: view.layoutMarginsGuide.leadingAnchor),
            label.trailingAnchor.constraint(equalTo: view.layoutMarginsGuide.trailingAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
        ])
    }

    @objc private func doneTapped() {
        done()
    }
}
