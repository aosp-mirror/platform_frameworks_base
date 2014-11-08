LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	IMountServiceListener.cpp \
	IMountShutdownObserver.cpp \
	IObbActionListener.cpp \
	IMountService.cpp

LOCAL_MODULE:= libstorage

LOCAL_CFLAGS += -Wall -Werror

include $(BUILD_STATIC_LIBRARY)
