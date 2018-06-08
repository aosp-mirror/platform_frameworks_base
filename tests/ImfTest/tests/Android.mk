LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests

# Include all test java files.
LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_JAVA_LIBRARIES := android.test.runner android.test.base
LOCAL_STATIC_JAVA_LIBRARIES := junit

LOCAL_PACKAGE_NAME := ImfTestTests
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_INSTRUMENTATION_FOR := ImfTest

include $(BUILD_PACKAGE)
