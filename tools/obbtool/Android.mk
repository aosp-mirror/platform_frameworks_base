#
# Copyright 2010 The Android Open Source Project
#
# Opaque Binary Blob (OBB) Tool
#

# This tool is prebuilt if we're doing an app-only build.
ifeq ($(TARGET_BUILD_APPS),)

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	Main.cpp

#LOCAL_C_INCLUDES +=

LOCAL_STATIC_LIBRARIES := \
	libutils \
	libcutils

ifeq ($(HOST_OS),linux)
LOCAL_LDLIBS += -lpthread
endif

LOCAL_MODULE := obbtool

include $(BUILD_HOST_EXECUTABLE)

endif # TARGET_BUILD_APPS
