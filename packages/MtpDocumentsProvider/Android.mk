LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional
LOCAL_SRC_FILES := $(call all-java-files-under, src)
LOCAL_PACKAGE_NAME := MtpDocumentsProvider
LOCAL_CERTIFICATE := media

include $(BUILD_PACKAGE)
include $(LOCAL_PATH)/tests/Android.mk
