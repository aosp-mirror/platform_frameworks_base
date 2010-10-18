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
	libcutils

ifeq ($(HOST_OS),linux)
LOCAL_LDLIBS += -lpthread
endif

LOCAL_MODULE := obbtool

include $(BUILD_HOST_EXECUTABLE)

# Non-Linux hosts might not have OpenSSL libcrypto
ifeq ($(HOST_OS),linux)
    include $(CLEAR_VARS)

    LOCAL_MODULE := pbkdf2gen

    LOCAL_MODULE_TAGS := optional

    LOCAL_CFLAGS := -Wall -Werror

    LOCAL_SRC_FILES := pbkdf2gen.cpp

    LOCAL_SHARED_LIBRARIES := libcrypto

    include $(BUILD_HOST_EXECUTABLE)
endif # HOST_OS == linux

endif # TARGET_BUILD_APPS
