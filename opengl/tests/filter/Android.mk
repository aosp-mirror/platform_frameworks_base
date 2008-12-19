LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	filter.c

LOCAL_SHARED_LIBRARIES := \
	libcutils \
    libGLES_CM \
    libui

LOCAL_MODULE:= test-opengl-filter

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)
