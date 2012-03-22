# FIXME remove "/../libmedia" at same time as rename
LOCAL_PATH := $(call my-dir)/../libmedia

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    AudioParameter.cpp
LOCAL_MODULE:= libmedia_helper
LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_LIBRARY)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    AudioEffect.cpp \
    AudioRecord.cpp \
    AudioSystem.cpp \
    AudioTrack.cpp \
    IAudioFlingerClient.cpp \
    IAudioFlinger.cpp \
    IAudioPolicyService.cpp \
    IAudioRecord.cpp \
    IAudioTrack.cpp \
    IEffectClient.cpp \
    IEffect.cpp \
    ToneGenerator.cpp

LOCAL_SHARED_LIBRARIES := \
    libaudioutils libbinder libcutils libutils

LOCAL_MODULE:= libmedia_native

LOCAL_C_INCLUDES := \
    $(call include-path-for, audio-utils)

LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
