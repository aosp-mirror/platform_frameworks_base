LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_AAPT_FLAGS := --shared-lib
LOCAL_PACKAGE_NAME := SharedLibrary

LOCAL_EXPORT_PACKAGE_RESOURCES := true
LOCAL_PRIVILEGED_MODULE := true
LOCAL_MODULE_TAGS := optional

LOCAL_PROGUARD_FLAG_FILES := proguard.proguard

include $(BUILD_PACKAGE)
