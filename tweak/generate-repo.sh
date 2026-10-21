#!/usr/bin/env bash
# Builds a Sileo/APT repository (flat, rootless) in ./public from the .deb files in ./public/debs.
# Keeps the 10 newest builds of each package (files are named <Name>-<version>-build-<N>.deb).
set -euo pipefail
cd "$(dirname "$0")/.."
cd public

python3 - <<'PY'
import re
from collections import defaultdict
from pathlib import Path

groups = defaultdict(list)
for deb in Path("debs").glob("*.deb"):
    m = re.match(r"^(?P<name>.+?)-[0-9][0-9.]*-build-(?P<build>[0-9]+)\.deb$", deb.name)
    if m:
        groups[m["name"]].append((int(m["build"]), deb))
for name, items in groups.items():
    items.sort()
    for _, old in items[:-10]:
        print("pruning", old.name)
        old.unlink()
PY

dpkg-scanpackages --multiversion debs /dev/null > Packages
gzip -9c Packages > Packages.gz
python3 - <<'PY'
from pathlib import Path
import hashlib, datetime
lines = [
    "Origin: Reynard (soum91)", "Label: Reynard (soum91)", "Suite: stable", "Codename: ios",
    "Version: 1.0", "Architectures: iphoneos-arm64", "Components: main",
    "Description: Reynard browser (Firefox Sync fork) and its tweaks, for Dopamine rootless",
    "Date: " + datetime.datetime.now(datetime.timezone.utc).strftime("%a, %d %b %Y %H:%M:%S +0000"),
    "SHA256:",
]
for name in ("Packages", "Packages.gz"):
    p = Path(name)
    lines.append(" " + hashlib.sha256(p.read_bytes()).hexdigest() + " " + str(p.stat().st_size) + " " + name)
Path("Release").write_text("\n".join(lines) + "\n")
PY
cat > index.html <<'HTML'
<!doctype html><html><head><meta name="viewport" content="width=device-width, initial-scale=1"><title>Reynard repo</title></head><body><h1>Reynard (soum91)</h1><p>Add this page's URL to Sileo's Sources. Packages are for Dopamine rootless: the Reynard app (jailbroken build) and the Reynard Default Browser tweak.</p></body></html>
HTML
