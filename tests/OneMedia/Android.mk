LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-subdir-java-files) \
        $(call all-Iaidl-files-under, src)

LOCAL_PACKAGE_NAME := OneMedia
LOCAL_CERTIFICATE := platform

LOCAL_STATIC_JAVA_LIBRARIES := \
        android-support-v7-appcompat \
        android-support-v7-mediarouter

LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)
