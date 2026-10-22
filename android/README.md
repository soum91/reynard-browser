# Reynard for Android TV

GeckoView browser for Google TV / Android TV that also runs on phones.

- Remote: the D-pad moves an on-screen pointer, OK clicks, pushing against a screen edge scrolls.
  Hold OK (or press Menu) for the toolbar: address bar, back/forward, reload, home, bookmarks,
  desktop/mobile site. Back goes back in history, then to the toolbar.
- Shows on the TV home screen (Leanback launcher banner), not only in Settings > Apps.
- Desktop site by default on TV; video sites get autoplay and DRM (Widevine) permission.
- Phones: normal touch browsing; the pointer only appears when a D-pad or gamepad is used.

Build: CI (`Build Android TV` workflow) produces `Reynard-TV-<version>-build-<n>.apk`.
Locally: `cd android && gradle assembleRelease` with the Android SDK installed.

Signing: set the `REYNARD_TV_KEYSTORE_B64` and `REYNARD_TV_KEYSTORE_PASSWORD` repository secrets
for release signing. Without them CI signs with the public test key in `app/debug.keystore`.
