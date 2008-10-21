# Copyright 2005 The Android Open Source Project

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	makekeycodes.cpp

LOCAL_MODULE := makekeycodes

include $(BUILD_HOST_EXECUTABLE)


