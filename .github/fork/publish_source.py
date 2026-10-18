"""Build source.json (AltStore / SideStore format) for the fork's nightly release.

Usage: publish_source.py <ipa> <download_url> <release_notes> <out_json>
Reads the version, build and bundle ID from the IPA itself, because AltStore
refuses an install when they don't match what the source says.
"""
import json
import plistlib
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

ipa_path, download_url, notes, out_path = sys.argv[1:5]

with zipfile.ZipFile(ipa_path) as ipa:
    plist_name = next(
        n for n in ipa.namelist()
        if n.startswith("Payload/") and n.count("/") == 2 and n.endswith(".app/Info.plist")
    )
    info = plistlib.loads(ipa.read(plist_name))

version = info["CFBundleShortVersionString"]
build = str(info["CFBundleVersion"])
bundle_id = info["CFBundleIdentifier"]
min_os = info.get("MinimumOSVersion", "13.0")
size = Path(ipa_path).stat().st_size
date = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

source = json.loads(Path(__file__).with_name("source-template.json").read_text(encoding="utf-8"))
app = source["apps"][0]
app["bundleIdentifier"] = bundle_id
app["versions"] = [{
    "version": version,
    "buildVersion": build,
    "date": date,
    "localizedDescription": notes,
    "downloadURL": download_url,
    "size": size,
    "minOSVersion": min_os,
}]
# Older clients (and some third-party sideloaders) read these flat fields.
app.update({
    "version": version,
    "versionDate": date,
    "versionDescription": notes,
    "downloadURL": download_url,
    "size": size,
})

Path(out_path).write_text(json.dumps(source, indent=2, ensure_ascii=False), encoding="utf-8")
print(f"{bundle_id} {version} ({build}), {size} bytes -> {out_path}")
