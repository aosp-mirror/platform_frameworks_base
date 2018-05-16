LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := ActivityTest
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_MODULE_TAGS := tests
LOCAL_CERTIFICATE := platform

# Disable AAPT2 to fix:
# frameworks/base/tests/ActivityTests/AndroidManifest.xml:42: error: unexpected element <preferred> found in <manifest><application><activity>.
# TODO(b/79755007): Re-enable AAPT2 when it supports the missing features.
LOCAL_USE_AAPT2 := false

include $(BUILD_PACKAGE)
