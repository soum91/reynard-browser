TARGET := iphone:clang:latest:16.0
ARCHS := arm64
THEOS_PACKAGE_SCHEME := rootless
INSTALL_TARGET_PROCESSES := SpringBoard
include $(THEOS)/makefiles/common.mk
TWEAK_NAME = ReynardDefault
ReynardDefault_FILES = Tweak.mm
ReynardDefault_CFLAGS = -fobjc-arc
ReynardDefault_FRAMEWORKS = UIKit Foundation
ReynardDefault_LIBRARIES = substrate
include $(THEOS_MAKE_PATH)/tweak.mk
