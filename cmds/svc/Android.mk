# Copyright 2007 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := $(call all-subdir-java-files)
LOCAL_MODULE := svclib
LOCAL_MODULE_STEM := svc
include $(BUILD_JAVA_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := svc
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_SRC_FILES := svc
LOCAL_REQUIRED_MODULES := svclib
include $(BUILD_PREBUILT)
