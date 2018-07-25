LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := ActivityTest
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_MODULE_TAGS := tests
LOCAL_CERTIFICATE := platform

LOCAL_USE_AAPT2 := true
# Disable AAPT2 manifest checks to fix:
# frameworks/base/tests/ActivityTests/AndroidManifest.xml:42: error: unexpected element <preferred> found in <manifest><application><activity>.
# TODO(b/79755007): Remove when AAPT2 recognizes the manifest elements.
LOCAL_AAPT_FLAGS += --warn-manifest-validation

include $(BUILD_PACKAGE)
