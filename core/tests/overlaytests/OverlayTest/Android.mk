LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_PACKAGE_NAME := OverlayTest

LOCAL_DEX_PREOPT := false

LOCAL_MODULE_PATH := $(TARGET_OUT)/app

LOCAL_SRC_FILES := $(call all-java-files-under, src)

include $(BUILD_PACKAGE)
