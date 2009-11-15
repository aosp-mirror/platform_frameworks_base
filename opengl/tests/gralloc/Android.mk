LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    gralloc.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
    libui

LOCAL_MODULE:= test-opengl-gralloc

LOCAL_MODULE_TAGS := optional

include $(BUILD_EXECUTABLE)
