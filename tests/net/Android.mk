#########################################################################
# Build FrameworksNetTests package
#########################################################################

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := $(call all-java-files-under, java)

LOCAL_STATIC_JAVA_LIBRARIES := \
    frameworks-base-testutils \
    framework-protos \
    android-support-test \
    mockito-target-minus-junit4 \
    platform-test-annotations \
    services.core \
    services.ipmemorystore \
    services.net

LOCAL_JAVA_LIBRARIES := \
    android.test.runner \
    android.test.base \
    android.test.mock

LOCAL_PACKAGE_NAME := FrameworksNetTests
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_COMPATIBILITY_SUITE := device-tests

LOCAL_CERTIFICATE := platform

LOCAL_ADDITIONAL_DEPENDENCIES := $(LOCAL_PATH)/Android.mk

include $(BUILD_PACKAGE)
