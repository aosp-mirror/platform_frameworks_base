LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        VorbisDecoder.cpp \

LOCAL_C_INCLUDES := \
        frameworks/base/media/libstagefright/include \
        external/tremolo

LOCAL_MODULE := libstagefright_vorbisdec

include $(BUILD_STATIC_LIBRARY)
