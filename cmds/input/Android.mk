# Copyright 2008 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := inputlib
LOCAL_MODULE_STEM := input
include $(BUILD_JAVA_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := input
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_SRC_FILES := input
LOCAL_REQUIRED_MODULES := inputlib
include $(BUILD_PREBUILT)
