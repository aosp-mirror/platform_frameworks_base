LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_SRC_FILES := $(call all-java-files-under, src)

LOCAL_STATIC_JAVA_LIBRARIES := \
    android-support-test \
    apct-perftests-utils \
    legacy-android-test

LOCAL_PACKAGE_NAME := CorePerfTests

LOCAL_JNI_SHARED_LIBRARIES := libperftestscore_jni

# Use google-fonts/dancing-script for the performance metrics
LOCAL_ASSET_DIR := $(TOP)/external/google-fonts/dancing-script

include $(BUILD_PACKAGE)

include $(call all-makefiles-under, $(LOCAL_PATH))
