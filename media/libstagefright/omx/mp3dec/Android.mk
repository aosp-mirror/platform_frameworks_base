LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    MP3Decoder.cpp

LOCAL_C_INCLUDES:= \
        $(TOP)/external/opencore/extern_libs_v2/khronos/openmax/include

LOCAL_SHARED_LIBRARIES:= \
        libstagefright_omx   \
        libutils

LOCAL_MODULE:= libstagefright_mp3

include $(BUILD_STATIC_LIBRARY)
