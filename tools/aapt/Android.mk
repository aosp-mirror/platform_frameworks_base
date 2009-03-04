# 
# Copyright 2006 The Android Open Source Project
#
# Android Asset Packaging Tool
#

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	AaptAssets.cpp \
	Command.cpp \
	Main.cpp \
	Package.cpp \
	StringPool.cpp \
	XMLNode.cpp \
	ResourceTable.cpp \
	Images.cpp \
	Resource.cpp \
    SourcePos.cpp

LOCAL_CFLAGS += -Wno-format-y2k

LOCAL_C_INCLUDES += external/expat/lib
LOCAL_C_INCLUDES += external/libpng
LOCAL_C_INCLUDES += external/zlib
LOCAL_C_INCLUDES += build/libs/host/include

#LOCAL_WHOLE_STATIC_LIBRARIES := 
LOCAL_STATIC_LIBRARIES := \
	libhost \
	libutils \
	libcutils \
	libexpat \
	libpng

LOCAL_LDLIBS := -lz

ifeq ($(HOST_OS),linux)
LOCAL_LDLIBS += -lrt
endif

ifeq ($(HOST_OS),windows)
ifeq ($(strip $(USE_CYGWIN),),)
LOCAL_LDLIBS += -lws2_32
endif
endif

LOCAL_MODULE := aapt

include $(BUILD_HOST_EXECUTABLE)

