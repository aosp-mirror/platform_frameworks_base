LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                 \
        MatroskaExtractor.cpp

LOCAL_C_INCLUDES:= \
        $(TOP)/external/libvpx/mkvparser \
        $(TOP)/frameworks/native/include/media/openmax \

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE:= libstagefright_matroska

include $(BUILD_STATIC_LIBRARY)
