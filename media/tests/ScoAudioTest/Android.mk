LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

#LOCAL_SDK_VERSION := current

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := scoaudiotest

include $(BUILD_PACKAGE)
