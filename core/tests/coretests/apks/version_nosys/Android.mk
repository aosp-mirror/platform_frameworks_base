LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_PACKAGE_NAME := version_1_nosys
LOCAL_AAPT_FLAGS := --version-code 1 --version-name 1.0
LOCAL_CERTIFICATE := $(LOCAL_PATH)/../../certs/unit_test
include $(FrameworkCoreTests_BUILD_PACKAGE)

