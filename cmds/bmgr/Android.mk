# Copyright 2007 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := bmgrlib
LOCAL_MODULE_STEM := bmgr
include $(BUILD_JAVA_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := bmgr
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_SRC_FILES := bmgr
LOCAL_REQUIRED_MODULES := bmgrlib
include $(BUILD_PREBUILT)
