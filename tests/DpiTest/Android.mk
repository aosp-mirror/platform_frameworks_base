LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := DensityTest
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_MODULE_TAGS := tests

LOCAL_AAPT_FLAGS = -c 120dpi,240dpi,160dpi,nodpi

include $(BUILD_PACKAGE)
