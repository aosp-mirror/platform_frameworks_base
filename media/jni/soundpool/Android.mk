LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	android_media_SoundPool.cpp \
	SoundPool.cpp \
	SoundPoolThread.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libbinder \
	libandroid_runtime \
	libnativehelper \
	libmedia \
	libmedia_native

LOCAL_MODULE:= libsoundpool

include $(BUILD_SHARED_LIBRARY)
