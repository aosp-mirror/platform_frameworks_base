LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        VorbisDecoder.cpp \

LOCAL_C_INCLUDES := \
        frameworks/base/media/libstagefright/include \
        external/tremolo \

LOCAL_MODULE := libstagefright_vorbisdec

include $(BUILD_STATIC_LIBRARY)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        SoftVorbis.cpp

LOCAL_C_INCLUDES := \
        external/tremolo \
        frameworks/base/media/libstagefright/include \
        frameworks/base/include/media/stagefright/openmax \

LOCAL_STATIC_LIBRARIES := \
        libstagefright_vorbisdec

LOCAL_SHARED_LIBRARIES := \
        libvorbisidec libstagefright libstagefright_omx \
        libstagefright_foundation libutils

LOCAL_MODULE := libstagefright_soft_vorbisdec
LOCAL_MODULE_TAGS := eng

include $(BUILD_SHARED_LIBRARY)

