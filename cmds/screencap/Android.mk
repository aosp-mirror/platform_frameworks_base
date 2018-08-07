LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    screencap.cpp

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
    libbinder \
    libhwui \
    libui \
    libgui

LOCAL_MODULE:= screencap

LOCAL_MODULE_TAGS := optional

LOCAL_CFLAGS += -Wall -Werror -Wunused -Wunreachable-code

include $(BUILD_EXECUTABLE)
