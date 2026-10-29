# Reynard for Android TV

GeckoView browser for Google TV / Android TV that also runs on phones.

- Remote: the D-pad moves an on-screen pointer, OK clicks, pushing against a screen edge scrolls.
  The tabs and toolbar now remain visible by default; D-pad Down from the toolbar focuses the
  webpage without hiding the controls. Use the menu's **Hide toolbar** to hide on demand,
  and press Back or Menu to restore it. Fullscreen video hides the toolbar temporarily.
- Shows on the TV home screen (Leanback launcher banner), not only in Settings > Apps.
- Desktop site by default on TV; video sites get autoplay and DRM (Widevine) permission.
- Phones: normal touch browsing; the pointer only appears when a D-pad or gamepad is used.
- TV menu includes tabs, private tabs, local history, bookmarks, download list, Firefox add-ons,
  search settings, website appearance and privacy toggles.

**Work in progress:** This Android Kotlin application does not yet have Mozilla account Sync,
cross-device bookmarks/history/passwords, or the segmented FetchDock download engine. The
Mozilla Account page explicitly identifies the missing backend; it does not collect credentials.
Native streaming saves Gecko's authenticated download response without a second request.
For direct public HTTPS file links, FetchDock can instead try four validated byte ranges (strong
ETag required) with single-stream fallback. Authenticated downloads are not yet segmented;
background resume and durable transfer queues are also not yet included.

Build: CI (`Build Android TV` workflow) produces `Reynard-TV-<version>-build-<n>.apk`.
Locally: `cd android && gradle assembleRelease` with the Android SDK installed.

Signing: set the `REYNARD_TV_KEYSTORE_B64` and `REYNARD_TV_KEYSTORE_PASSWORD` repository secrets
for release signing. Without them CI signs with the public test key in `app/debug.keystore`.
