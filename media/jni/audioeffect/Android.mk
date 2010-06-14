LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	android_media_AudioEffect.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libandroid_runtime \
	libnativehelper \
	libmedia

LOCAL_MODULE:= libaudioeffect_jni

include $(BUILD_SHARED_LIBRARY)
