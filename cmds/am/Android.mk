# Copyright 2008 The Android Open Source Project
#
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)
LOCAL_SRC_FILES := \
    $(call all-java-files-under, src) \
    $(call all-proto-files-under, proto)
LOCAL_MODULE := am
LOCAL_PROTOC_OPTIMIZE_TYPE := stream
include $(BUILD_JAVA_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := am
LOCAL_SRC_FILES := am
LOCAL_MODULE_CLASS := EXECUTABLES
LOCAL_MODULE_TAGS := optional
include $(BUILD_PREBUILT)
