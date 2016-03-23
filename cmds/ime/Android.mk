# Copyright 2007 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := imelib
LOCAL_MODULE_STEM := ime
include $(BUILD_JAVA_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := ime
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_SRC_FILES := ime
LOCAL_REQUIRED_MODULES := imelib
include $(BUILD_PREBUILT)
