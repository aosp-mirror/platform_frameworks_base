LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := $(call all-java-files-under, src)

# Notice that we don't have to include the src files of SmokeTestApp because, by
# running the tests using an instrumentation targeting SmokeTestApp, we
# automatically get all of its classes loaded into our environment.

LOCAL_PACKAGE_NAME := SmokeTest

LOCAL_INSTRUMENTATION_FOR := SmokeTestApp

LOCAL_SDK_VERSION := 8

include $(BUILD_PACKAGE)

