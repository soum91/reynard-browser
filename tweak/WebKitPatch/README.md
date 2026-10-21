# WebKit Patch (experimental)

Target: Dopamine rootless, iOS 16.x (built for an iPhone on 16.1.1).

The system WebKit cannot be replaced from a tweak, and a tweak cannot add engine features that are
missing from the binary. This tweak narrows the gap from the app side. It hooks `WKWebView` in any
process that loads WebKit (Safari, SFSafariViewController, third-party apps) and:

- injects JavaScript polyfills at document start in every frame (on by default),
- optionally advertises a newer Safari version in the user agent,
- optionally enables WebKit feature flags that this build ships disabled.

Polyfilled: `Object.groupBy`, `Map.groupBy`, `Promise.withResolvers`, `Array.fromAsync`, change-array-by-copy
methods, `findLast`, `Object.hasOwn`, well-formed string methods, the new `Set` methods, `URL.canParse`,
`AbortSignal.timeout/any`, `Response.json`, async iteration of `ReadableStream`, `requestIdleCallback`,
`Element.checkVisibility`, Iterator helpers. Each one only installs when the engine lacks it.

Not possible this way: new syntax (regex `v` flag, `using`), new CSS (nesting, `text-wrap: balance`),
new rendering or media features. Those need the engine itself.

## Settings

Settings app > WebKit Patch (needs PreferenceLoader, pulled in as a dependency) has switches for the master
switch, polyfills, the user agent option and the Safari version. The rest is plist-only.

Plist at `/var/jb/var/mobile/Library/Preferences/com.soum91.webkitpatch.plist`:

| key | default | meaning |
| --- | --- | --- |
| `enabled` | true | master switch |
| `polyfills` | true | inject the polyfill script |
| `spoofUserAgent` | false | add `Version/<safariVersion> ... Safari/604.1` when the app sets no app name |
| `safariVersion` | 17.6 | version used by the user agent option |
| `enableFeatures` | [] | WebKit feature keys to switch on |
| `logFeatures` | false | log every feature key available in this WebKit build (subsystem `com.soum91.webkitpatch`) |
| `excludedApps` | [] | bundle IDs to leave alone |
| `excludedHosts` | [] | hostnames where no polyfill runs |

Changes apply to web views created afterwards, so relaunch the app. Spoofing the user agent can make
sites send syntax this WebKit cannot parse, so turn it on per need.
