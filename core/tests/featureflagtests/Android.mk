LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := \
    $(call all-java-files-under, src)

LOCAL_DX_FLAGS := --core-library
LOCAL_STATIC_JAVA_LIBRARIES := android-common frameworks-core-util-lib android-support-test
LOCAL_JAVA_LIBRARIES := android.test.runner android.test.base
LOCAL_PACKAGE_NAME := FrameworksCoreFeatureFlagTests
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_CERTIFICATE := platform
LOCAL_COMPATIBILITY_SUITE := device-tests

include $(BUILD_PACKAGE)
