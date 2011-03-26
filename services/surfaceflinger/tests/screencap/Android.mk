LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	screencap.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libbinder \
	libskia \
    libui \
    libgui

LOCAL_MODULE:= test-screencap

LOCAL_MODULE_TAGS := tests

LOCAL_C_INCLUDES += \
	external/skia/include/core \
	external/skia/include/effects \
	external/skia/include/images \
	external/skia/src/ports \
	external/skia/include/utils

include $(BUILD_EXECUTABLE)
