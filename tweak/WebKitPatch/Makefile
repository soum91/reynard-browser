TARGET := iphone:clang:latest:16.0
ARCHS := arm64 arm64e
THEOS_PACKAGE_SCHEME := rootless
INSTALL_TARGET_PROCESSES := MobileSafari
include $(THEOS)/makefiles/common.mk
TWEAK_NAME = WebKitPatch
WebKitPatch_FILES = Tweak.mm Polyfills.mm
WebKitPatch_CFLAGS = -fobjc-arc
WebKitPatch_FRAMEWORKS = UIKit Foundation WebKit
WebKitPatch_LIBRARIES = substrate
include $(THEOS_MAKE_PATH)/tweak.mk

SUBPROJECTS += prefs
include $(THEOS_MAKE_PATH)/aggregate.mk
