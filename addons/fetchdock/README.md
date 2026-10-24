# FetchDock Companion for the iOS fork

Bundled on the `firefox-account-sync` branch as `fetchdock@soum91` using Gecko's existing built-in extension mechanism. Mozilla signature requirements for separately installed add-ons remain enabled. Only the exact `resource://app/extensions/fetchdock/` package is added to the iOS built-in allowlist.

Open the FetchDock Companion entry in Reynard's site/add-on menu. It lists direct HTTPS file/media links and can send the current URL. Turn on **Catch file links on this site** to show a Download with FetchDock choice when a regular file link is clicked. The per-site setting is off by default. Native navigation delegates handle the explicit FetchDock link in both ordinary pages and add-on popups.

Requires FetchDock installed on the same iPhone/iPad. The add-on passes only the URL, without browser cookies. Blob links, DRM streams and arbitrary JavaScript/POST exports aren't extracted. A normal page URL downloads HTML. This is not cross-device transfer from Android TV to iPhone.

The accompanying XPI is an unsigned developer/source package. Use the companion-enabled fork build for installation; it is not presented as a Mozilla-signed add-on. This branch retains its existing Firefox account and AutoFill work.

The dedicated workflow validates JavaScript and native handoff syntax, builds only the normal iOS variant with existing build caches, and publishes a separate companion IPA without replacing the existing nightly release or Android TV build. Bundle identifiers are preserved for update installation with the same signing identity.
