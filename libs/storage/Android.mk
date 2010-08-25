LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	IMountServiceListener.cpp \
	IMountShutdownObserver.cpp \
	IObbActionListener.cpp \
	IMountService.cpp

LOCAL_STATIC_LIBRARIES := \
	libutils \
	libbinder

LOCAL_MODULE:= libstorage

ifeq ($(TARGET_SIMULATOR),true)
    LOCAL_LDLIBS += -lpthread
endif

include $(BUILD_STATIC_LIBRARY)
