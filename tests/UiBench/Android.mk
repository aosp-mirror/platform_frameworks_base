LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests
LOCAL_SDK_VERSION := current
LOCAL_MIN_SDK_VERSION := 21

# omit gradle 'build' dir
LOCAL_SRC_FILES := $(call all-java-files-under,src)

# use appcompat/support lib from the tree, so improvements/
# regressions are reflected in test data
LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res

LOCAL_USE_AAPT2 := true

LOCAL_STATIC_ANDROID_LIBRARIES := \
    android-support-design \
    android-support-v4 \
    android-support-v7-appcompat \
    android-support-v7-cardview \
    android-support-v7-recyclerview \
    android-support-v17-leanback

LOCAL_PACKAGE_NAME := UiBench

LOCAL_COMPATIBILITY_SUITE := device-tests

include $(BUILD_PACKAGE)
