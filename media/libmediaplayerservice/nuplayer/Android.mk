LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                       \
        NuPlayer.cpp                    \
        NuPlayerDecoder.cpp             \
        NuPlayerDriver.cpp              \
        NuPlayerRenderer.cpp            \
        NuPlayerStreamListener.cpp      \
        DecoderWrapper.cpp              \

LOCAL_C_INCLUDES := \
        $(TOP)/frameworks/base/include/media/stagefright/openmax        \
	$(TOP)/frameworks/base/media/libstagefright/include             \
        $(TOP)/frameworks/base/media/libstagefright/mpeg2ts             \

LOCAL_MODULE:= libstagefright_nuplayer

LOCAL_MODULE_TAGS := eng

include $(BUILD_STATIC_LIBRARY)

