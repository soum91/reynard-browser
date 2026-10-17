//
//  SceneDelegate.swift
//  Reynard
//
//  Created by Minh Ton on 1/2/26.
//

import UIKit

class SceneDelegate: UIResponder, UIWindowSceneDelegate {
    var window: UIWindow?
    
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        guard let windowScene = (scene as? UIWindowScene) else { return }
        
        let browserViewController = BrowserViewController()
        browserViewController.sessionManager.setApplicationForeground(scene.activationState != .background)
        
        let window = UIWindow(windowScene: windowScene)
        window.overrideUserInterfaceStyle = AppAppearanceController.userInterfaceStyle(for: Prefs.AppearanceSettings.appAppearance)
        window.rootViewController = browserViewController
        window.makeKeyAndVisible()
        self.window = window

        FxAReceiveTab.shared.onTabsReceived = { [weak self] tabs in
            self?.presentReceivedTabs(tabs)
        }

        handleIncomingURLContexts(connectionOptions.urlContexts)
    }
    
    func scene(_ scene: UIScene, openURLContexts URLContexts: Set<UIOpenURLContext>) {
        handleIncomingURLContexts(URLContexts)
    }
    
    func sceneDidDisconnect(_ scene: UIScene) {}
    
    func sceneDidBecomeActive(_ scene: UIScene) {
        guard let browserViewController = window?.rootViewController as? BrowserViewController else {
            return
        }
        
        browserViewController.startScreenOrientationHandling()
        browserViewController.sessionManager.applicationDidBecomeActive()
        browserViewController.tabManager.applicationDidBecomeActive()
        SyncManager.shared.requestSync(reason: .foreground)
        // Tabs other devices sent to this one arrive while the app is open.
        FxAReceiveTab.shared.startPolling()
    }

    func sceneWillResignActive(_ scene: UIScene) {
        guard let browserViewController = window?.rootViewController as? BrowserViewController else {
            return
        }

        FxAReceiveTab.shared.stopPolling()
        browserViewController.stopScreenOrientationHandling()
        browserViewController.tabManager.applicationWillResignActive()
        browserViewController.sessionManager.applicationWillResignActive()
    }
    
    func windowScene(
        _ windowScene: UIWindowScene,
        didUpdate previousCoordinateSpace: UICoordinateSpace,
        interfaceOrientation previousInterfaceOrientation: UIInterfaceOrientation,
        traitCollection previousTraitCollection: UITraitCollection
    ) {
        (window?.rootViewController as? BrowserViewController)?
            .screenOrientationChanged(to: windowScene.interfaceOrientation)
    }
    
    func sceneWillEnterForeground(_ scene: UIScene) {
        (window?.rootViewController as? BrowserViewController)?
            .sessionManager.setApplicationForeground(true)
    }
    
    func sceneDidEnterBackground(_ scene: UIScene) {
        (window?.rootViewController as? BrowserViewController)?
            .sessionManager.setApplicationForeground(false)
        // Keeps this device's open tabs current for your other Firefoxes.
        SyncManager.shared.requestSync(reason: .background)
    }
    
    private func presentReceivedTabs(_ tabs: [FxAReceivedTab]) {
        guard let browserViewController = window?.rootViewController as? BrowserViewController,
              let first = tabs.first else {
            return
        }

        var lines: [String] = []
        if tabs.count == 1 {
            lines.append(first.title)
            lines.append(first.url.absoluteString)
        } else {
            lines.append(String(format: NSLocalizedString("%d tabs", comment: ""), tabs.count))
        }
        if let sender = first.senderName {
            lines.append(String(format: NSLocalizedString("From %@", comment: ""), sender))
        }

        let alert = UIAlertController(
            title: NSLocalizedString("Tab Received", comment: ""),
            message: lines.joined(separator: "\n"),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: NSLocalizedString("Open", comment: ""), style: .default) { _ in
            browserViewController.loadViewIfNeeded()
            browserViewController.sidebarCoordinator.loadContentIfNeeded()
            for tab in tabs {
                browserViewController.sidebarCoordinator.openExternalURL(tab.url)
            }
        })
        alert.addAction(UIAlertAction(title: NSLocalizedString("Dismiss", comment: ""), style: .cancel))

        var presenter: UIViewController = browserViewController
        while let next = presenter.presentedViewController, !(next is UIAlertController) {
            presenter = next
        }
        presenter.present(alert, animated: true)
    }

    private func handleIncomingURLContexts(_ urlContexts: Set<UIOpenURLContext>) {
        guard let incomingURL = urlContexts.first?.url else {
            return
        }
        handleIncomingURL(incomingURL)
    }
    
    private func handleIncomingURL(_ incomingURL: URL) {
        guard let browserViewController = window?.rootViewController as? BrowserViewController,
              let resolvedURL = resolvedBrowserURL(from: incomingURL) else {
            return
        }
        
        DispatchQueue.main.async {
            browserViewController.loadViewIfNeeded()
            browserViewController.sidebarCoordinator.loadContentIfNeeded()
            browserViewController.sidebarCoordinator.openExternalURL(resolvedURL)
        }
    }
    
    private func resolvedBrowserURL(from incomingURL: URL) -> URL? {
        guard let scheme = incomingURL.scheme?.lowercased() else {
            return nil
        }
        
        if scheme == "http" || scheme == "https" {
            return incomingURL
        }
        
        guard scheme == "reynard",
              let components = URLComponents(url: incomingURL, resolvingAgainstBaseURL: false),
              let encodedURL = components.queryItems?.first(where: { $0.name == "url" })?.value else {
            return nil
        }
        
        return URL(string: encodedURL)
    }
}
