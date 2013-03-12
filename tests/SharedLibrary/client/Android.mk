LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_APK_LIBRARIES := SharedLibrary

LOCAL_PACKAGE_NAME := SharedLibraryClient

LOCAL_MODULE_TAGS := tests

include $(BUILD_PACKAGE)
