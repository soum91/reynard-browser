#!/usr/bin/env python3
"""Refresh Reynard's bundled app icons in an existing IPA/TIPA.

This script does not compile Swift, Gecko, or the other application frameworks.
The icon catalog and optimized loose icon PNGs must first be generated with
Apple's `actool` from the exact same Assets.xcassets directory.
"""

import argparse
import plistlib
import re
import tempfile
import zipfile
from pathlib import Path


APP_ROOT = "Payload/Reynard.app/"
REPLACEMENTS = (
    "Assets.car",
    "ReynardFox60x60@2x.png",
    "ReynardFox76x76@2x~ipad.png",
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--assets", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--version", required=True)
    parser.add_argument("--build", required=True)
    args = parser.parse_args()

    if not re.fullmatch(r"\d+(?:\.\d+){1,2}", args.version):
        parser.error("--version must be a numeric iOS version, e.g. 0.18.1")
    if not re.fullmatch(r"\d+(?:\.\d+){0,2}", args.build):
        parser.error("--build must be numeric")
    if args.input.resolve() == args.output.resolve():
        parser.error("The output must not overwrite the original IPA")

    assets = {APP_ROOT + name: (args.assets / name).read_bytes() for name in REPLACEMENTS}
    info_path = APP_ROOT + "Info.plist"

    with zipfile.ZipFile(args.input) as source:
        present = set(source.namelist())
        missing = (set(assets) | {info_path}) - present
        if missing:
            raise ValueError(f"IPA does not contain the expected Reynard files: {sorted(missing)}")

        info = plistlib.loads(source.read(info_path))
        if info.get("CFBundleIcons", {}).get("CFBundleAlternateIcons", {}).get("ReynardFox") is None:
            raise ValueError("IPA has no registered ReynardFox alternate icon")
        metadata_paths = [info_path] + [
            name for name in source.namelist()
            if name.startswith(APP_ROOT + "PlugIns/") and name.endswith(".appex/Info.plist")
        ]
        for path in metadata_paths:
            metadata = info if path == info_path else plistlib.loads(source.read(path))
            metadata["CFBundleShortVersionString"] = args.version
            metadata["CFBundleVersion"] = args.build
            assets[path] = plistlib.dumps(metadata, fmt=plistlib.FMT_BINARY)

        args.output.parent.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=args.output.parent) as temporary:
            candidate = Path(temporary) / args.output.name
            with zipfile.ZipFile(candidate, "w", allowZip64=True) as target:
                for item in source.infolist():
                    # Preserve member permissions, timestamps, and compression.
                    target.writestr(item, assets.get(item.filename, source.read(item)))

            with zipfile.ZipFile(candidate) as verification:
                if verification.testzip() is not None:
                    raise ValueError("Patched IPA failed ZIP integrity verification")
                new_info = plistlib.loads(verification.read(info_path))
                assert new_info["CFBundleShortVersionString"] == args.version
                assert new_info["CFBundleVersion"] == args.build
                for path, value in assets.items():
                    assert verification.read(path) == value, path

            candidate.replace(args.output)

    print(f"Updated {args.output} to {args.version} ({args.build}), without recompiling Gecko")


if __name__ == "__main__":
    main()
