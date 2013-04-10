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

LOCAL_CFLAGS := -Wall -Werror

#LOCAL_C_INCLUDES +=

LOCAL_STATIC_LIBRARIES := \
	libutils \
	libandroidfw \
	libcutils \
	liblog

ifeq ($(HOST_OS),linux)
LOCAL_LDLIBS += -ldl -lpthread
endif

LOCAL_MODULE := obbtool

include $(BUILD_HOST_EXECUTABLE)

#####################################################
include $(CLEAR_VARS)

LOCAL_MODULE := pbkdf2gen
LOCAL_MODULE_TAGS := optional
LOCAL_CFLAGS := -Wall -Werror
LOCAL_SRC_FILES := pbkdf2gen.cpp
LOCAL_LDLIBS += -ldl
LOCAL_C_INCLUDES := external/openssl/include $(LOCAL_C_INCLUDES)
LOCAL_STATIC_LIBRARIES := libcrypto_static

include $(BUILD_HOST_EXECUTABLE)

#######################################################
endif # TARGET_BUILD_APPS
