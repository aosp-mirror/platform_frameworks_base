LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	tritex.c

LOCAL_SHARED_LIBRARIES := \
	libcutils \
    libGLES_CM \
    libui

LOCAL_MODULE:= test-opengl-tritex

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)
