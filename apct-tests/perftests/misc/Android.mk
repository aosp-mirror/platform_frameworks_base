LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_STATIC_JAVA_LIBRARIES := \
    android-support-test \
    apct-perftests-utils

LOCAL_PACKAGE_NAME := MiscPerfTests

include $(BUILD_PACKAGE)

