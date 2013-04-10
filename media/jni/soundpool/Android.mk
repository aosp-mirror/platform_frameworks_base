LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	android_media_SoundPool.cpp

LOCAL_SHARED_LIBRARIES := \
	liblog \
	libcutils \
	libutils \
	libandroid_runtime \
	libnativehelper \
	libmedia

LOCAL_MODULE:= libsoundpool

include $(BUILD_SHARED_LIBRARY)
