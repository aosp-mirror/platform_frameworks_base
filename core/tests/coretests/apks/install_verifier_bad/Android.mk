LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := install_verifier_bad

# Disable AAPT2 to fix:
# frameworks/base/core/tests/coretests/apks/install_verifier_bad/AndroidManifest.xml:19: error: unexpected element <package-verifier> found in <manifest>.
# TODO(b/79755007): Re-enable AAPT2 when it supports the missing features.
LOCAL_USE_AAPT2 := false

include $(FrameworkCoreTests_BUILD_PACKAGE)
