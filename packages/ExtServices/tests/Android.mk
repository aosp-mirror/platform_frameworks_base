LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests
LOCAL_CERTIFICATE := platform

LOCAL_JAVA_LIBRARIES := android.test.runner android.test.base

LOCAL_STATIC_JAVA_LIBRARIES := \
    android-support-test \
    mockito-target-minus-junit4 \
    espresso-core \
    truth-prebuilt \
    testables

# Include all test java files.
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := ExtServicesUnitTests
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_INSTRUMENTATION_FOR := ExtServices

include $(BUILD_PACKAGE)
