LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    com_android_commands_hid_Device.cpp

LOCAL_C_INCLUDES := \
    $(JNI_H_INCLUDE)

LOCAL_LDLIBS += -landroid -llog -lnativehelper

LOCAL_MODULE := libhidcommand_jni
LOCAL_MODULE_TAGS := optional

LOCAL_CFLAGS += -Wall -Wextra -Werror

include $(BUILD_SHARED_LIBRARY)
