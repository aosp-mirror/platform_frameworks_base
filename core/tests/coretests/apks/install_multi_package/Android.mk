LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := install_multi_package

LOCAL_USE_AAPT2 := true
# Disable AAPT2 manifest checks to fix:
# frameworks/base/core/tests/coretests/apks/install_multi_package/AndroidManifest.xml:46: error: unexpected element <package> found in <manifest>.
# TODO(b/79755007): Remove when AAPT2 recognizes the manifest elements.
LOCAL_AAPT_FLAGS += --warn-manifest-validation

include $(FrameworkCoreTests_BUILD_PACKAGE)
#include $(BUILD_PACKAGE)

