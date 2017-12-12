LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := install_split_feature_a

LOCAL_USE_AAPT2 := true
LOCAL_AAPT_FLAGS += --custom-package com.google.android.dexapis.splitapp.feature_a
LOCAL_AAPT_FLAGS += --package-id 0x80

include $(FrameworkCoreTests_BUILD_PACKAGE)