LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    com_android_commands_hid_Device.cpp

LOCAL_C_INCLUDES := \
    $(JNI_H_INCLUDE) \
    frameworks/base/core/jni

LOCAL_SHARED_LIBRARIES := \
    libandroid_runtime \
    liblog \
    libnativehelper \
    libutils

LOCAL_MODULE := libhidcommand_jni
LOCAL_MODULE_TAGS := optional

LOCAL_CFLAGS += -Wall -Wextra -Werror

include $(BUILD_SHARED_LIBRARY)
