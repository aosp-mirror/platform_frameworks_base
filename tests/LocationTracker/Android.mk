LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := LocationTracker

LOCAL_MODULE_TAGS := tests

include $(BUILD_PACKAGE)
