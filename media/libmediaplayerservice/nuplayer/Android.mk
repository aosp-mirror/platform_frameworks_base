LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                       \
        HTTPLiveSource.cpp              \
        NuPlayer.cpp                    \
        NuPlayerDecoder.cpp             \
        NuPlayerDriver.cpp              \
        NuPlayerRenderer.cpp            \
        NuPlayerStreamListener.cpp      \
        RTSPSource.cpp                  \
        StreamingSource.cpp             \

LOCAL_C_INCLUDES := \
        $(TOP)/frameworks/base/include/media/stagefright/openmax        \
	$(TOP)/frameworks/base/media/libstagefright/include             \
        $(TOP)/frameworks/base/media/libstagefright/mpeg2ts             \
        $(TOP)/frameworks/base/media/libstagefright/httplive            \
        $(TOP)/frameworks/base/media/libstagefright/rtsp                \

LOCAL_MODULE:= libstagefright_nuplayer

LOCAL_MODULE_TAGS := eng

include $(BUILD_STATIC_LIBRARY)

