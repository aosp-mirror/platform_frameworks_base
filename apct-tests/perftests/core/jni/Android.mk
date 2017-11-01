LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_SDK_VERSION := 21

LOCAL_SRC_FILES:= \
    SystemPerfTest.cpp \

LOCAL_C_INCLUDES += \
    $(JNI_H_INCLUDE)

LOCAL_MODULE := libperftestscore_jni
LOCAL_MODULE_TAGS := optional

LOCAL_CFLAGS += -Wall -Werror -Wunused -Wunreachable-code

include $(BUILD_SHARED_LIBRARY)
