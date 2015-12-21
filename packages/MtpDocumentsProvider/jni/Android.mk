LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    com_android_mtp_AppFuse.cpp

LOCAL_SHARED_LIBRARIES := \
    libandroid_runtime \
    libnativehelper \
    liblog

LOCAL_CFLAGS += -Wall -Werror -Wunused -Wunreachable-code
LOCAL_MODULE := libappfuse_jni

include $(BUILD_SHARED_LIBRARY)
