LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                 \
        ESDS.cpp                  \
        MediaBuffer.cpp           \
        MediaBufferGroup.cpp      \
        MediaDefs.cpp             \
        MediaSource.cpp           \
        MetaData.cpp              \
        OMXCodec.cpp              \
        Utils.cpp                 \
        OMXClient.cpp

ifeq ($(BUILD_WITH_FULL_STAGEFRIGHT),true)

LOCAL_SRC_FILES +=                \
        AMRExtractor.cpp          \
        AMRWriter.cpp             \
        AudioPlayer.cpp           \
        AudioSource.cpp           \
        AwesomePlayer.cpp         \
        CachingDataSource.cpp     \
        CameraSource.cpp          \
        DataSource.cpp            \
        FileSource.cpp            \
        HTTPDataSource.cpp        \
        HTTPStream.cpp            \
        JPEGSource.cpp            \
        MP3Extractor.cpp          \
        MPEG4Extractor.cpp        \
        MPEG4Writer.cpp           \
        MediaExtractor.cpp        \
        SampleIterator.cpp        \
        SampleTable.cpp           \
        ShoutcastSource.cpp       \
        StagefrightMediaScanner.cpp \
        StagefrightMetadataRetriever.cpp \
        TimeSource.cpp            \
        TimedEventQueue.cpp       \
        WAVExtractor.cpp          \
        string.cpp

LOCAL_CFLAGS += -DBUILD_WITH_FULL_STAGEFRIGHT
endif

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
        $(TOP)/external/opencore/extern_libs_v2/khronos/openmax/include \
        $(TOP)/external/opencore/android \
        $(TOP)/external/tremor/Tremor

LOCAL_SHARED_LIBRARIES := \
        libbinder         \
        libmedia          \
        libutils          \
        libcutils         \
        libui             \
        libsonivox        \
        libvorbisidec

ifeq ($(BUILD_WITH_FULL_STAGEFRIGHT),true)

LOCAL_STATIC_LIBRARIES := \
        libstagefright_aacdec \
        libstagefright_amrnbdec \
        libstagefright_amrnbenc \
        libstagefright_amrwbdec \
        libstagefright_avcdec \
        libstagefright_m4vh263dec \
        libstagefright_mp3dec \
        libstagefright_id3

LOCAL_SHARED_LIBRARIES += \
        libstagefright_amrnb_common \
        libstagefright_avc_common \
        libstagefright_color_conversion

endif

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
        LOCAL_LDLIBS += -lpthread
endif

LOCAL_CFLAGS += -Wno-multichar

LOCAL_PRELINK_MODULE:= false

LOCAL_MODULE:= libstagefright

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
