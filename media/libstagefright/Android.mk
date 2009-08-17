LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:=                 \
        CachingDataSource.cpp     \
        DataSource.cpp            \
        FileSource.cpp            \
        HTTPDataSource.cpp        \
        HTTPStream.cpp            \
        MP3Extractor.cpp          \
        MPEG4Extractor.cpp        \
        MPEG4Writer.cpp           \
        MediaBuffer.cpp           \
        MediaBufferGroup.cpp      \
        MediaExtractor.cpp        \
        MediaPlayerImpl.cpp       \
        MediaSource.cpp           \
        MetaData.cpp              \
        MmapSource.cpp            \
        OMXCodec.cpp              \
        SampleTable.cpp           \
        ShoutcastSource.cpp       \
        TimeSource.cpp            \
        TimedEventQueue.cpp       \
        Utils.cpp                 \
        AudioPlayer.cpp           \
        ESDS.cpp                  \
        OMXClient.cpp             \
        OMXDecoder.cpp            \
        string.cpp

LOCAL_C_INCLUDES:= \
        $(TOP)/external/opencore/extern_libs_v2/khronos/openmax/include \
        $(TOP)/external/opencore/android

LOCAL_SHARED_LIBRARIES := \
        libbinder         \
        libmedia          \
        libutils          \
        libcutils         \
        libui

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
        LOCAL_LDLIBS += -lpthread
endif

LOCAL_CFLAGS += -Wno-multichar

LOCAL_PRELINK_MODULE:= false

LOCAL_MODULE:= libstagefright

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
