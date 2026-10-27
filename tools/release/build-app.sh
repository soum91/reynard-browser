#!/bin/sh

set -eu

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/../.." && pwd)"
DIST_DIR="$ROOT_DIR/dist"
PROJECT_PATH="$ROOT_DIR/browser/Reynard.xcodeproj"
XCCONFIG_PATH="$ROOT_DIR/browser/Configuration/Reynard.xcconfig"
BUILD_XCCONFIG_PATH="$DIST_DIR/Reynard.xcconfig"

NO_SIGNING=false
NIGHTLY=false

for argument in "$@"; do
	case "$argument" in
		--no-signing)
			NO_SIGNING=true
			;;
		--nightly)
			NIGHTLY=true
			;;
		*) ;;
	esac
done

rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

cp "$XCCONFIG_PATH" "$BUILD_XCCONFIG_PATH"

# An opaque commit SHA is not a valid sortable CFBundleVersion for sideloaders.
# Unix minutes provide a monotonically increasing numeric build value.
BUILD_NUMBER=$(($(date -u +%s) / 60))
sed -i '' -E \
	-e "s|^CURRENT_BUILD = .*|CURRENT_BUILD = $BUILD_NUMBER|" \
	"$BUILD_XCCONFIG_PATH"

if [ "$NIGHTLY" = true ]; then
	if [ -n "${GITHUB_RUN_NUMBER:-}" ]; then
		# Every Nightly run advances the visible three-part version, even when
		# only resources changed. This prevents FlareStore showing an old build.
		BASE_VERSION=$(sed -n 's/^CURRENT_VERSION = //p' "$BUILD_XCCONFIG_PATH")
		PREFIX=$(printf '%s' "$BASE_VERSION" | cut -d . -f 1-2)
		BASE_PATCH=$(printf '%s' "$BASE_VERSION" | cut -d . -f 3)
		case "$BASE_PATCH:$GITHUB_RUN_NUMBER" in
			*[!0-9:]*|:*|*:) echo "Invalid Nightly version or run number" >&2; exit 1 ;;
		esac
		PATCH=$((BASE_PATCH + GITHUB_RUN_NUMBER))
		sed -i '' -E \
			-e "s|^CURRENT_VERSION = .*|CURRENT_VERSION = $PREFIX.$PATCH|" \
			"$BUILD_XCCONFIG_PATH"
	else
		sed -i '' -E \
			-e 's|^(CURRENT_VERSION = [0-9.]+)$|\1-dev|' \
			"$BUILD_XCCONFIG_PATH"
	fi
	sed -i '' -E \
	-e 's|^APP_DISPLAY_NAME = .*|APP_DISPLAY_NAME = Reynard (Nightly)|' \
	"$BUILD_XCCONFIG_PATH"
fi

run_xcodebuild() {
	xcodebuild archive \
		-scheme "Reynard" \
		-archivePath "$DIST_DIR/Reynard.xcarchive" \
		-project "$PROJECT_PATH" \
		-sdk iphoneos \
		-arch arm64 \
		-configuration Release \
		-xcconfig "$BUILD_XCCONFIG_PATH" \
		"$@"
}

if [ "$NO_SIGNING" = true ]; then
	run_xcodebuild \
		CODE_SIGNING_ALLOWED=NO \
		CODE_SIGNING_REQUIRED=NO \
		CODE_SIGN_IDENTITY="" \
		PROVISIONING_PROFILE_SPECIFIER=""

	# Strip bitcode because the archive bypasses signing: https://developer.apple.com/documentation/Xcode-Release-Notes/xcode-13_3_1-release-notes?changes=_1
	SWIFT_CONCURRENCY_PATH="$DIST_DIR/Reynard.xcarchive/Products/Applications/Reynard.app/Frameworks/libswift_Concurrency.dylib"
	xcrun bitcode_strip "$SWIFT_CONCURRENCY_PATH" -r -o "$SWIFT_CONCURRENCY_PATH"
	/usr/bin/codesign --force --sign - --verbose "$SWIFT_CONCURRENCY_PATH"
else
	run_xcodebuild
fi
