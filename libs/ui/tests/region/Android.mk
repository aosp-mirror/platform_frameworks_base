LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	region.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
    libui

LOCAL_MODULE:= test-region

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)
