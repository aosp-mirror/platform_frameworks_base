LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= bugreport.c

LOCAL_MODULE:= bugreport

LOCAL_SHARED_LIBRARIES := libcutils

include $(BUILD_EXECUTABLE)
