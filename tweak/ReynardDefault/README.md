# Reynard Default Browser (tweak)

Dopamine rootless tweak that sends web links apps hand to Safari to Reynard instead.

It hooks `-[UIApplication openURL:]`, `openURL:options:completionHandler:` and `SFSafariViewController`
in every UIKit app, and rewrites `http(s)://...` to `reynard://open?url=<encoded>`, which Reynard handles.

## What it does not do

- It does not replace WebKit inside other apps. `WKWebView` pages (in-app browsers, Mail's renderer)
  are untouched; only link handoffs to Safari are redirected.
- It does not change the iOS "Default Browser App" setting. If Reynard's jailbroken build shows up under
  Settings > Reynard > Default Browser App, turn that on too; the tweak is the fallback for apps that
  hardcode Safari or use `SFSafariViewController`.
- Sign-in hosts (Google, Apple, Microsoft) and App Store / Maps style links are left alone on purpose.

## Settings

`/var/jb/var/mobile/Library/Preferences/com.soum91.reynarddefault.plist`, optional keys:
`enabled` (bool), `redirectSafariViewController` (bool), `targetBundleID` (string, default
`com.minh-ton.Reynard`), `excludedHosts` (array, `host` or `host/path-prefix`), `excludedApps` (bundle IDs).
Changes apply within a few seconds.
