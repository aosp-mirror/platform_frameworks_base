LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

include frameworks/base/media/libstagefright/codecs/common/Config.mk

LOCAL_SRC_FILES:=                         \
        ACodec.cpp                        \
        AACExtractor.cpp                  \
        AMRExtractor.cpp                  \
        AMRWriter.cpp                     \
        AudioPlayer.cpp                   \
        AudioSource.cpp                   \
        AwesomePlayer.cpp                 \
        CameraSource.cpp                  \
        CameraSourceTimeLapse.cpp         \
        VideoSourceDownSampler.cpp        \
        DataSource.cpp                    \
        DRMExtractor.cpp                  \
        ESDS.cpp                          \
        FileSource.cpp                    \
        FLACExtractor.cpp                 \
        HTTPBase.cpp                      \
        HTTPStream.cpp                    \
        JPEGSource.cpp                    \
        MP3Extractor.cpp                  \
        MPEG2TSWriter.cpp                 \
        MPEG4Extractor.cpp                \
        MPEG4Writer.cpp                   \
        MediaBuffer.cpp                   \
        MediaBufferGroup.cpp              \
        MediaDefs.cpp                     \
        MediaExtractor.cpp                \
        MediaSource.cpp                   \
        MediaSourceSplitter.cpp           \
        MetaData.cpp                      \
        NuCachedSource2.cpp               \
        NuHTTPDataSource.cpp              \
        OMXClient.cpp                     \
        OMXCodec.cpp                      \
        OggExtractor.cpp                  \
        SampleIterator.cpp                \
        SampleTable.cpp                   \
        ShoutcastSource.cpp               \
        StagefrightMediaScanner.cpp       \
        StagefrightMetadataRetriever.cpp  \
        ThreadedSource.cpp                \
        ThrottledSource.cpp               \
        TimeSource.cpp                    \
        TimedEventQueue.cpp               \
        Utils.cpp                         \
        VBRISeeker.cpp                    \
        WAVExtractor.cpp                  \
        WVMExtractor.cpp                  \
        XINGSeeker.cpp                    \
        avc_utils.cpp                     \

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
        $(TOP)/frameworks/base/include/media/stagefright/openmax \
        $(TOP)/external/flac/include \
        $(TOP)/external/tremolo \
        $(TOP)/frameworks/base/media/libstagefright/rtsp \
        $(TOP)/external/openssl/include \

LOCAL_SHARED_LIBRARIES := \
        libbinder         \
        libmedia          \
        libutils          \
        libcutils         \
        libui             \
        libsonivox        \
        libvorbisidec     \
        libsurfaceflinger_client \
        libstagefright_yuv \
        libcamera_client \
        libdrmframework  \
        libcrypto        \
        libssl           \
        libgui           \

LOCAL_STATIC_LIBRARIES := \
        libstagefright_color_conversion \
        libstagefright_aacdec \
        libstagefright_aacenc \
        libstagefright_amrnbdec \
        libstagefright_amrnbenc \
        libstagefright_amrwbdec \
        libstagefright_amrwbenc \
        libstagefright_avcdec \
        libstagefright_avcenc \
        libstagefright_m4vh263dec \
        libstagefright_m4vh263enc \
        libstagefright_mp3dec \
        libstagefright_vorbisdec \
        libstagefright_matroska \
        libstagefright_vpxdec \
        libvpx \
        libstagefright_mpeg2ts \
        libstagefright_httplive \
        libstagefright_rtsp \
        libstagefright_id3 \
        libstagefright_g711dec \
        libFLAC \

################################################################################

# The following was shamelessly copied from external/webkit/Android.mk and
# currently must follow the same logic to determine how webkit was built and
# if it's safe to link against libchromium.net

# V8 also requires an ARMv7 CPU, and since we must use jsc, we cannot
# use the Chrome http stack either.
ifneq ($(strip $(ARCH_ARM_HAVE_ARMV7A)),true)
  USE_ALT_HTTP := true
endif

# See if the user has specified a stack they want to use
HTTP_STACK = $(HTTP)
# We default to the Chrome HTTP stack.
DEFAULT_HTTP = chrome
ALT_HTTP = android

ifneq ($(HTTP_STACK),chrome)
  ifneq ($(HTTP_STACK),android)
    # No HTTP stack is specified, pickup the one we want as default.
    ifeq ($(USE_ALT_HTTP),true)
      HTTP_STACK = $(ALT_HTTP)
    else
      HTTP_STACK = $(DEFAULT_HTTP)
    endif
  endif
endif

ifeq ($(HTTP_STACK),chrome)

LOCAL_SHARED_LIBRARIES += \
        liblog           \
        libicuuc         \
        libicui18n       \
        libz             \
        libdl            \

LOCAL_STATIC_LIBRARIES += \
        libstagefright_chromium_http \
        libchromium_net         \
        libwebcore              \

ifneq ($(TARGET_SIMULATOR),true)
LOCAL_SHARED_LIBRARIES += libstlport
include external/stlport/libstlport.mk
endif

LOCAL_CPPFLAGS += -DCHROMIUM_AVAILABLE=1

endif  # ifeq ($(HTTP_STACK),chrome)

################################################################################

LOCAL_SHARED_LIBRARIES += \
        libstagefright_amrnb_common \
        libstagefright_enc_common \
        libstagefright_avc_common \
        libstagefright_foundation \

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
        LOCAL_LDLIBS += -lpthread -ldl
        LOCAL_SHARED_LIBRARIES += libdvm
        LOCAL_CPPFLAGS += -DANDROID_SIMULATOR
endif

ifneq ($(TARGET_SIMULATOR),true)
LOCAL_SHARED_LIBRARIES += libdl
endif

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
        LOCAL_LDLIBS += -lpthread
endif

LOCAL_CFLAGS += -Wno-multichar

LOCAL_MODULE:= libstagefright

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
