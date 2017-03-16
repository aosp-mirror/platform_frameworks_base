LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_JAVA_LIBRARIES += legacy-test

LOCAL_SDK_VERSION := current

LOCAL_PACKAGE_NAME := com.android.overlaytest.second_app_overlay

include $(BUILD_PACKAGE)
