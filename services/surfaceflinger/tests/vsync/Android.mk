LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	vsync.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libbinder \
    libui \
    libgui

LOCAL_MODULE:= test-vsync-events

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)
