TARGET := iphone:clang:latest:16.0
ARCHS := arm64 arm64e
THEOS_PACKAGE_SCHEME := rootless
include $(THEOS)/makefiles/common.mk
BUNDLE_NAME = WebKitPatchPrefs
WebKitPatchPrefs_FILES = WKPRootListController.m
WebKitPatchPrefs_INSTALL_PATH = /Library/PreferenceBundles
WebKitPatchPrefs_FRAMEWORKS = UIKit
WebKitPatchPrefs_CFLAGS = -fobjc-arc
WebKitPatchPrefs_LDFLAGS = -undefined dynamic_lookup
include $(THEOS_MAKE_PATH)/bundle.mk
