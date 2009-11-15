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
        CachingDataSource.cpp     \
        DataSource.cpp            \
        FileSource.cpp            \
        HTTPDataSource.cpp        \
        HTTPStream.cpp            \
        JPEGSource.cpp            \
        MediaExtractor.cpp        \
        MP3Extractor.cpp          \
        MPEG4Extractor.cpp        \
        MPEG4Writer.cpp           \
        MediaPlayerImpl.cpp       \
        MmapSource.cpp            \
        SampleTable.cpp           \
        ShoutcastSource.cpp       \
        TimeSource.cpp            \
        TimedEventQueue.cpp       \
        AudioPlayer.cpp           \
        stagefright_string.cpp

endif

LOCAL_C_INCLUDES:= \
	$(JNI_H_INCLUDE) \
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
