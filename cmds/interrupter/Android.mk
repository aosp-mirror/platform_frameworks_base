LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    interrupter.c
LOCAL_MODULE := interrupter
LOCAL_MODULE_TAGS := eng tests
LOCAL_LDFLAGS := -ldl
LOCAL_CFLAGS := -Wall -Werror -Wunused -Wunreachable-code

include $(BUILD_SHARED_LIBRARY)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    interrupter.c
LOCAL_MODULE := interrupter
LOCAL_MODULE_TAGS := eng tests
LOCAL_LDFLAGS := -ldl
LOCAL_CFLAGS := -Wall -Werror -Wunused -Wunreachable-code

include $(BUILD_HOST_SHARED_LIBRARY)
