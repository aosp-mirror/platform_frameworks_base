LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res
LOCAL_SRC_FILES := \
  $(call all-java-files-under, src) \
  src/android/os/ISomeService.aidl

LOCAL_STATIC_JAVA_LIBRARIES := \
    androidx.appcompat_appcompat \
    androidx.test.rules \
    androidx.annotation_annotation \
    apct-perftests-overlay-apps \
    apct-perftests-resources-manager-apps \
    apct-perftests-utils \
    guava

LOCAL_JAVA_LIBRARIES := android.test.base

LOCAL_PACKAGE_NAME := CorePerfTests
LOCAL_PRIVATE_PLATFORM_APIS := true

LOCAL_JNI_SHARED_LIBRARIES := libperftestscore_jni

# Use google-fonts/dancing-script for the performance metrics
LOCAL_ASSET_DIR := $(TOP)/external/google-fonts/dancing-script

LOCAL_COMPATIBILITY_SUITE += device-tests
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)