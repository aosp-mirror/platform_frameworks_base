LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_MANIFEST_FILE := app/src/main/AndroidManifest.xml

# omit gradle 'build' dir
LOCAL_SRC_FILES := $(call all-java-files-under,app/src/main/java)

LOCAL_RESOURCE_DIR := \
    $(LOCAL_PATH)/app/src/main/res

LOCAL_AAPT_FLAGS := \
    --auto-add-overlay

LOCAL_PACKAGE_NAME := TouchLatency
LOCAL_SDK_VERSION := current

LOCAL_COMPATIBILITY_SUITE := device-tests

include $(BUILD_PACKAGE)
