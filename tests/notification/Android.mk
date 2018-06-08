LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := android.test.runner.stubs
LOCAL_PACKAGE_NAME := NotificationTests

LOCAL_SDK_VERSION := 21

include $(BUILD_PACKAGE)

