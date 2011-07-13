LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        SoftVPX.cpp

LOCAL_C_INCLUDES := \
        $(TOP)/external/libvpx \
        $(TOP)/external/libvpx/vpx_codec \
        $(TOP)/external/libvpx/vpx_ports \
        frameworks/base/media/libstagefright/include \
        frameworks/base/include/media/stagefright/openmax \

LOCAL_STATIC_LIBRARIES := \
        libvpx

LOCAL_SHARED_LIBRARIES := \
        libstagefright libstagefright_omx libstagefright_foundation libutils

LOCAL_MODULE := libstagefright_soft_vpxdec
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)

