LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := test

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := StatusBarTest
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
