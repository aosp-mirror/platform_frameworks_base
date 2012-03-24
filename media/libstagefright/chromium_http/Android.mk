LOCAL_PATH:= $(call my-dir)

ifneq ($(TARGET_BUILD_PDK), true)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=       \
        DataUriSource.cpp \
        ChromiumHTTPDataSource.cpp \
        support.cpp

LOCAL_C_INCLUDES:= \
        frameworks/base/media/libstagefright \
        $(TOP)/frameworks/native/include/media/openmax \
        external/chromium \
        external/chromium/android

LOCAL_CFLAGS += -Wno-multichar

LOCAL_SHARED_LIBRARIES += libstlport
include external/stlport/libstlport.mk

LOCAL_MODULE:= libstagefright_chromium_http

include $(BUILD_STATIC_LIBRARY)
endif
