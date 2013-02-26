# 
# Copyright 2006 The Android Open Source Project
#
# Android Asset Packaging Tool
#

LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    file_utils.cpp \
    localize.cpp \
    merge_res_and_xliff.cpp \
    res_check.cpp \
    xmb.cpp \
    Configuration.cpp \
    Perforce.cpp \
    SourcePos.cpp \
    Values.cpp \
    ValuesFile.cpp \
    XLIFFFile.cpp \
    XMLHandler.cpp

LOCAL_C_INCLUDES := \
    external/expat/lib \
    build/libs/host/include

LOCAL_CFLAGS += -g -O0

LOCAL_STATIC_LIBRARIES := \
    libexpat \
    libhost \
    libutils \
	libcutils
    
ifeq ($(HOST_OS),linux)
LOCAL_LDLIBS += -lrt -ldl -lpthread
endif


LOCAL_MODULE := localize

ifeq (a,a)
    LOCAL_CFLAGS += -DLOCALIZE_WITH_TESTS
    LOCAL_SRC_FILES += \
        test.cpp \
        localize_test.cpp \
        merge_res_and_xliff_test.cpp \
        Perforce_test.cpp \
        ValuesFile_test.cpp \
        XLIFFFile_test.cpp \
        XMLHandler_test.cpp
endif

include $(BUILD_HOST_EXECUTABLE)
