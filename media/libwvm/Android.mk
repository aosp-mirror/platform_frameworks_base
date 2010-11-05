# create an empty mk for libwvm
# for integration purpose
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE := libwvm

LOCAL_MODULE_TAGS := optional
include $(BUILD_SHARED_LIBRARY)
