LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	IMountServiceListener.cpp \
	IMountShutdownObserver.cpp \
	IObbActionListener.cpp \
	IMountService.cpp

LOCAL_MODULE:= libstorage

LOCAL_EXPORT_C_INCLUDE_DIRS := $(LOCAL_PATH)/include
LOCAL_C_INCLUDES := $(LOCAL_PATH)/include

LOCAL_CFLAGS += -Wall -Werror

LOCAL_SHARED_LIBRARIES := libbinder

include $(BUILD_STATIC_LIBRARY)
