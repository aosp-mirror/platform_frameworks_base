
LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_JAVA_LIBRARIES := android.test.runner

LOCAL_PACKAGE_NAME := ExternalStorageProviderTests
LOCAL_INSTRUMENTATION_FOR := ExternalStorageProvider

LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
