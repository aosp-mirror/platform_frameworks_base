LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE_TAGS := tests

LOCAL_MODULE := loadclass

include $(BUILD_JAVA_LIBRARY)
