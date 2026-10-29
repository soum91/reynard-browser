# Reynard TV changelog

## Unreleased: TV browser foundation

### Fixed
- Toolbar remains visible during normal TV browsing, tab switching, address submission and menu actions.
- Pressing D-pad Down from the toolbar now focuses the webpage without dismissing the navigation controls.
- Toolbar can be hidden explicitly from the menu, and restored via Menu or Back; fullscreen video still hides it temporarily.

### Added
- Remote-friendly settings menu with search engine selection, homepage URL, desktop/mobile switching and website color scheme.
- Local browsing history and history clearing, excluding private tabs.
- Private tab creation and bookmark deletion controls.
- GeckoView add-on management: signed XPI installation with permission approval, listing, enable/disable, updates and uninstall.
- Gecko-authenticated downloads to the system Downloads provider on modern Android, with an in-memory transfer status list.
- Find-in-page and autoplay/DRM/tracking protection controls.

### Not yet included
- Mozilla Account sign-in, Firefox Sync engines and Android credential integration.
- FetchDock segmented downloads, durable background transfers and persistent transfer queue.
- Fully featured site permission management, reading mode and custom launcher icon picker.

The Android TV branch is a separate Kotlin application: iOS-specific Swift features must be
ported to Android rather than directly merged from `firefox-account-sync`.
