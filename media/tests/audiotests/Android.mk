
LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_MODULE:= shared_mem_test

LOCAL_SRC_FILES := \
    shared_mem_test.cpp

LOCAL_SHARED_LIBRARIES :=  \
    libc \
    libcutils \
    libutils \
    libbinder \
    libhardware_legacy \
    libmedia

LOCAL_MODULE_TAGS := tests

LOCAL_CFLAGS += -Wall -Werror -Wunused -Wunreachable-code

include $(BUILD_EXECUTABLE)
