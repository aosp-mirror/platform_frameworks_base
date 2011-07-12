LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := screenshot.c

LOCAL_MODULE := screenshot

LOCAL_SHARED_LIBRARIES := libcutils libz
LOCAL_STATIC_LIBRARIES := libpng
LOCAL_C_INCLUDES += external/zlib

include $(BUILD_EXECUTABLE)
