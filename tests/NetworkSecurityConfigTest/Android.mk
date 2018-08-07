LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

# We only want this apk build for tests.
LOCAL_MODULE_TAGS := tests
LOCAL_CERTIFICATE := platform

LOCAL_JAVA_LIBRARIES := \
    android.test.runner \
    bouncycastle \
    conscrypt \
    android.test.base \

LOCAL_STATIC_JAVA_LIBRARIES := junit

# Include all test java files.
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := NetworkSecurityConfigTests
LOCAL_PRIVATE_PLATFORM_APIS := true

include $(BUILD_PACKAGE)
