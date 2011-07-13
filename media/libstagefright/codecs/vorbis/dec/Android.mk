LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        SoftVorbis.cpp

LOCAL_C_INCLUDES := \
        external/tremolo \
        frameworks/base/media/libstagefright/include \
        frameworks/base/include/media/stagefright/openmax \

LOCAL_SHARED_LIBRARIES := \
        libvorbisidec libstagefright libstagefright_omx \
        libstagefright_foundation libutils

LOCAL_MODULE := libstagefright_soft_vorbisdec
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)

