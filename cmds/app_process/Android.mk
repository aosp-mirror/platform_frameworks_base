LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	app_main.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libandroid_runtime

LOCAL_MODULE:= app_process

include $(BUILD_EXECUTABLE)
