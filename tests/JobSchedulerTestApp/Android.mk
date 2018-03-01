LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_PACKAGE_NAME := JobSchedulerTestApp
LOCAL_SDK_VERSION := current

LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)

