LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := ActivityTest
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_MODULE_TAGS := tests
LOCAL_CERTIFICATE := platform

LOCAL_USE_AAPT2 := true

include $(BUILD_PACKAGE)
