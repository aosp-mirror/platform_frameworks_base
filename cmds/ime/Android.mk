# Copyright 2007 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := ime
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_SRC_FILES := ime
include $(BUILD_PREBUILT)
