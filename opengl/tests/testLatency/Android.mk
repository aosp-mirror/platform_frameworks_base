#########################################################################
# Test end-to-end latency.
#########################################################################

TOP_LOCAL_PATH:= $(call my-dir)

# Build activity


LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SDK_VERSION := 8
LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := TestLatency

include $(BUILD_PACKAGE)
