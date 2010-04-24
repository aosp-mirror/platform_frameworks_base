LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := DensityTest

LOCAL_MODULE_TAGS := tests

LOCAL_AAPT_FLAGS = -c 120dpi -c 240dpi -c 160dpi

include $(BUILD_PACKAGE)
