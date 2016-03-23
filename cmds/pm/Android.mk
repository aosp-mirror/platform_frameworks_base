# Copyright 2007 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := pmlib
LOCAL_MODULE_STEM := pm
include $(BUILD_JAVA_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := pm
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_SRC_FILES := pm
LOCAL_REQUIRED_MODULES := pmlib
include $(BUILD_PREBUILT)
