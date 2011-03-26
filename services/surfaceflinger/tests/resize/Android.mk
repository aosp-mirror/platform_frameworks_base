LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	resize.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
    libui \
    libgui

LOCAL_MODULE:= test-resize

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)
