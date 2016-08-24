LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	touchlag.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils libutils \

LOCAL_MODULE:= test-touchlag

LOCAL_CFLAGS += -Wall -Wextra -Werror

LOCAL_MODULE_TAGS := tests

include $(BUILD_EXECUTABLE)
