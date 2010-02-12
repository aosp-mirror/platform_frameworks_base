LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                     \
        ColorConverter.cpp            \
        SoftwareRenderer.cpp

LOCAL_C_INCLUDES := \
        $(TOP)/external/opencore/extern_libs_v2/khronos/openmax/include

LOCAL_SHARED_LIBRARIES :=       \
        libbinder               \
        libmedia                \
        libutils                \
        libui                   \
        libcutils				\
        libsurfaceflinger_client\
        libcamera_client

LOCAL_PRELINK_MODULE:= false

LOCAL_MODULE:= libstagefright_color_conversion

include $(BUILD_SHARED_LIBRARY)
