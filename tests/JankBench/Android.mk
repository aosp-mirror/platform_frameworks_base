LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_MANIFEST_FILE := app/src/main/AndroidManifest.xml

LOCAL_SDK_VERSION := current

LOCAL_USE_AAPT2 := true

# omit gradle 'build' dir
LOCAL_SRC_FILES := $(call all-java-files-under,app/src/main/java)

# use appcompat/support lib from the tree, so improvements/
# regressions are reflected in test data
LOCAL_RESOURCE_DIR := \
    $(LOCAL_PATH)/app/src/main/res \


LOCAL_STATIC_ANDROID_LIBRARIES := \
    $(ANDROID_SUPPORT_DESIGN_TARGETS) \
    android-support-v4 \
    android-support-v7-appcompat \
    android-support-v7-cardview \
    android-support-v7-recyclerview \
    android-support-v17-leanback \

LOCAL_STATIC_JAVA_LIBRARIES := \
    apache-commons-math \
    junit


LOCAL_PACKAGE_NAME := JankBench

LOCAL_COMPATIBILITY_SUITE := device-tests

include $(BUILD_PACKAGE)
