# Copyright 2009 The Android Open Source Project

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= backup.cpp

LOCAL_SHARED_LIBRARIES := libcutils libutils libandroidfw

LOCAL_MODULE:= btool

LOCAL_MODULE_PATH := $(TARGET_OUT_OPTIONAL_EXECUTABLES)
LOCAL_MODULE_TAGS := optional

include $(BUILD_EXECUTABLE)
