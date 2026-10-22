//
//  LoginListViewController.swift
//  Reynard AutoFill
//

import UIKit

final class LoginListViewController: UITableViewController, UISearchResultsUpdating {
    var onSelect: ((SyncedLogin) -> Void)?
    var onCancel: (() -> Void)?

    private let allLogins: [SyncedLogin]
    private let hosts: [String]
    private var suggested: [SyncedLogin] = []
    private var others: [SyncedLogin] = []
    private let searchController = UISearchController(searchResultsController: nil)

    init(logins: [SyncedLogin], hosts: [String]) {
        allLogins = logins
        self.hosts = hosts
        super.init(style: .insetGrouped)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = NSLocalizedString("Firefox Passwords", comment: "")
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            barButtonSystemItem: .cancel, target: self, action: #selector(cancelTapped)
        )
        searchController.searchResultsUpdater = self
        searchController.obscuresBackgroundDuringPresentation = false
        navigationItem.searchController = searchController
        navigationItem.hidesSearchBarWhenScrolling = false
        tableView.register(UITableViewCell.self, forCellReuseIdentifier: "login")
        filter(query: "")
    }

    @objc private func cancelTapped() {
        onCancel?()
    }

    func updateSearchResults(for searchController: UISearchController) {
        filter(query: searchController.searchBar.text ?? "")
    }

    private func filter(query: String) {
        let trimmed = query.trimmingCharacters(in: .whitespaces).lowercased()
        let matching = trimmed.isEmpty ? allLogins : allLogins.filter {
            $0.host.contains(trimmed) || $0.username.lowercased().contains(trimmed)
        }
        suggested = matching.filter { login in hosts.contains { login.matches(host: $0) } }
        others = trimmed.isEmpty && !suggested.isEmpty ? [] : matching.filter { login in !hosts.contains { login.matches(host: $0) } }
        tableView.reloadData()
    }

    private enum Section {
        case suggested, others, empty
    }

    private var sections: [Section] {
        var result: [Section] = []
        if !suggested.isEmpty {
            result.append(.suggested)
        }
        if !others.isEmpty {
            result.append(.others)
        }
        return result.isEmpty ? [.empty] : result
    }

    private func login(at indexPath: IndexPath) -> SyncedLogin {
        sections[indexPath.section] == .suggested ? suggested[indexPath.row] : others[indexPath.row]
    }

    override func numberOfSections(in tableView: UITableView) -> Int {
        sections.count
    }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        switch sections[section] {
        case .suggested: return suggested.count
        case .others: return others.count
        case .empty: return 0
        }
    }

    override func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        switch sections[section] {
        case .suggested: return NSLocalizedString("For This Website", comment: "")
        case .others: return NSLocalizedString("All Passwords", comment: "")
        case .empty: return nil
        }
    }

    override func tableView(_ tableView: UITableView, titleForFooterInSection section: Int) -> String? {
        guard sections[section] == .empty else {
            return nil
        }
        if allLogins.isEmpty {
            return NSLocalizedString("No passwords yet. Turn on Passwords in Reynard › Settings › Firefox Account and sync.", comment: "")
        }
        return NSLocalizedString("No matching passwords.", comment: "")
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .subtitle, reuseIdentifier: "login")
        let login = login(at: indexPath)
        cell.textLabel?.text = login.username.isEmpty ? NSLocalizedString("(no username)", comment: "") : login.username
        cell.detailTextLabel?.text = login.host
        cell.detailTextLabel?.textColor = .secondaryLabel
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        onSelect?(login(at: indexPath))
    }
}
