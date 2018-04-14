LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_PACKAGE_NAME := SimAppDialog
LOCAL_PRIVATE_PLATFORM_APIS := true
LOCAL_CERTIFICATE := platform


LOCAL_STATIC_ANDROID_LIBRARIES := \
    androidx.legacy_legacy-support-v4

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
include frameworks/opt/setupwizard/library/common-platform-deprecated.mk

include $(BUILD_PACKAGE)
