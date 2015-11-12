LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

# omit gradle 'build' dir
LOCAL_SRC_FILES := $(call all-java-files-under,src)

# use appcompat/support lib from the tree, so improvements/
# regressions are reflected in test data
LOCAL_RESOURCE_DIR := \
    $(LOCAL_PATH)/res \
    frameworks/support/v7/appcompat/res \
    frameworks/support/v7/cardview/res \
    frameworks/support/v7/recyclerview/res

LOCAL_AAPT_FLAGS := \
    --auto-add-overlay \
    --extra-packages android.support.v7.appcompat \
    --extra-packages android.support.v7.cardview \
    --extra-packages android.support.v7.recyclerview

LOCAL_STATIC_JAVA_LIBRARIES := \
    android-support-v4 \
    android-support-v7-appcompat \
    android-support-v7-cardview \
    android-support-v7-recyclerview

LOCAL_PACKAGE_NAME := UiBench

include $(BUILD_PACKAGE)
