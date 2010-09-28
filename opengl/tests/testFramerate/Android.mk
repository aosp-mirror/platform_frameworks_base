#########################################################################
# Test framerate and look for hiccups
#########################################################################

TOP_LOCAL_PATH:= $(call my-dir)

# Build activity


LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := $(call all-subdir-java-files)

LOCAL_PACKAGE_NAME := TestFramerate

include $(BUILD_PACKAGE)
