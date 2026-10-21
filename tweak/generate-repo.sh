#!/usr/bin/env bash
# Builds a Sileo/APT repository (flat, rootless) in ./public from the .deb files in ./public/debs.
set -euo pipefail
cd "$(dirname "$0")/.."
cd public
dpkg-scanpackages --multiversion debs /dev/null > Packages
gzip -9c Packages > Packages.gz
python3 - <<'PY'
from pathlib import Path
import hashlib, datetime
lines = [
    "Origin: Reynard Tweaks", "Label: Reynard Tweaks (soum91)", "Suite: stable", "Codename: ios",
    "Version: 1.0", "Architectures: iphoneos-arm64", "Components: main",
    "Description: Tweaks for the Reynard browser fork, for Dopamine rootless",
    "Date: " + datetime.datetime.now(datetime.timezone.utc).strftime("%a, %d %b %Y %H:%M:%S +0000"),
    "SHA256:",
]
for name in ("Packages", "Packages.gz"):
    p = Path(name)
    lines.append(" " + hashlib.sha256(p.read_bytes()).hexdigest() + " " + str(p.stat().st_size) + " " + name)
Path("Release").write_text("\n".join(lines) + "\n")
PY
cat > index.html <<'HTML'
<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Reynard tweaks repo</title></head><body><h1>Reynard tweaks</h1><p>Add this page's URL to Sileo's Sources. Packages are for Dopamine rootless.</p></body></html>
HTML
