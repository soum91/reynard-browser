# Reynard TV changelog

## Android TV Mozilla Account experiment (test branch only)
- Set up an isolated `android-tv-fxa-experiment` workflow and `.fxatest` app identity so OAuth/Sync experiments cannot overwrite the stable browser.
- Experimental CI builds publish Action artifacts only; the production TV release remains unchanged.
- Added private Mozilla Account sign-in test using Mozilla's Android account manager (OAuth PKCE,
  scoped-key handling, Keystore-backed account persistence) and a built-in GeckoView WebChannel extension.
- Restricted account messages to top-level `https://accounts.firefox.com`, with native sender validation.
- Added Sign in / Sign out to the Account settings and clear messaging that Firefox Sync stores
  are **not** connected. OAuth compatibility still requires a real-device sign-in test.
- Test-only use of Firefox Android's existing public client ID. A dedicated Reynard registration
  is required before public distribution.

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
- Add-on options pages (including Gecko's internal moz-extension URLs) and a Mozilla Add-ons discovery link.
- In-app GitHub update checks comparing published APK build numbers with the installed version; direct update APK download to the TV Downloads folder.
- Gecko-authenticated downloads to the system Downloads provider on modern Android, with an in-memory transfer status list.
- Experimental FetchDock direct-URL transfers: four validated HTTP byte-range segments when the server supports strong ETags and identity coding, with safe single-stream fallback.
- Find-in-page and autoplay/DRM/tracking protection controls.
- Selective clearing of Gecko website cookies, caches, sessions and permissions; fixes for private tabs accidentally persisting the last URL or opening non-private popup tabs.

### Not yet included
- Firefox Sync engines on Android TV (bookmarks, history, passwords, tabs).
- Interception of ordinary authenticated downloads for segmented FetchDock processing, durable background transfers and persistent transfer queue.
- Fully featured site permission management, reading mode and custom launcher icon picker.

The Android TV branch is a separate Kotlin application: iOS-specific Swift features must be
ported to Android rather than directly merged from `firefox-account-sync`.
