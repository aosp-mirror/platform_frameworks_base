
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := android.test.runner

LOCAL_STATIC_JAVA_LIBRARIES := \
    android-support-test \
    mockito-target-minus-junit4 \
    ub-uiautomator \
    junit \
    legacy-android-test \

LOCAL_PACKAGE_NAME := ShellTests
LOCAL_COMPATIBILITY_SUITE := device-tests
LOCAL_INSTRUMENTATION_FOR := Shell

LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
