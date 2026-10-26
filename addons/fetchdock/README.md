# FetchDock built into Reynard

The `firefox-account-sync` branch includes the FetchDock segmented transfer engine directly. The separate FetchDock app is not required. Supported HTTPS GET downloads use four parallel connections inside Reynard's existing Downloads screen, including progress, pause/resume, saved files and background URLSession delivery.

The server must support byte ranges and supply a strong ETag. The engine validates Content-Range, identity encoding, validator and byte counts before streaming chunks into one file. Unsafe or unsupported range responses fall back to a full single connection. Completed chunks survive pause/relaunch; unfinished chunks restart.

Normal browser downloads reuse the originating Gecko request's authentication when handed to the native engine. Private-mode downloads, POST exports, non-resumable resources and small files retain Gecko's existing transfer path. Companion selections navigate in the current browser tab so Gecko authenticates the request before download interception. Explicit fetchdock scheme links are handled inside Reynard.

The optional companion lists direct file links and offers per-site capture, off by default. It does not extract blobs or DRM streams. An ordinary page URL is still a webpage. External XPI signature requirements remain enabled; this companion is bundled into the fork.

JIT setup updates validate remote pairing files before atomic replacement, retain the detailed tunnel-library cause, and show wrapped, scrollable error text. These are setup and diagnostics improvements, not proof that JIT is enabled on a device. JIT still needs a valid Remote Pairing (RPPairing) record, working LocalDevVPN and suitable device signing/setup.

The dedicated workflow validates JavaScript, pairing-file preservation, Swift syntax, the shared engine against the iOS 13 SDK target, and Objective-C tunnel support. It builds the normal iOS variant and publishes a separate IPA. Firefox sync, AutoFill and existing bundle identifiers are retained. Android TV is unchanged.
