LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_USE_AAPT2 := true
LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_RES_LIBRARIES := SharedLibrary

LOCAL_PACKAGE_NAME := SharedLibraryClient
LOCAL_SDK_VERSION := current

LOCAL_MODULE_TAGS := tests

include $(BUILD_PACKAGE)
