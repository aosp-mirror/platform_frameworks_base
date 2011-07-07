LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	android_media_AudioEffect.cpp \
	android_media_Visualizer.cpp

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libandroid_runtime \
	libnativehelper \
	libmedia

LOCAL_C_INCLUDES := \
	system/media/audio_effects/include

LOCAL_MODULE:= libaudioeffect_jni

include $(BUILD_SHARED_LIBRARY)
