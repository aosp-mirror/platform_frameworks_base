LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        VPXDecoder.cpp

LOCAL_MODULE := libstagefright_vpxdec

LOCAL_C_INCLUDES := \
        $(TOP)/frameworks/base/media/libstagefright/include \
        $(TOP)/external/opencore/extern_libs_v2/khronos/openmax/include \
        $(TOP)/external/libvpx \
        $(TOP)/external/libvpx/vpx_codec \
        $(TOP)/external/libvpx/vpx_ports

include $(BUILD_STATIC_LIBRARY)
