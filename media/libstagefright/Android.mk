ifeq ($(BUILD_WITH_STAGEFRIGHT),true)

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
        QComHardwareRenderer.cpp  \
	SampleTable.cpp           \
	ShoutcastSource.cpp       \
        SoftwareRenderer.cpp      \
        SurfaceRenderer.cpp       \
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

LOCAL_CFLAGS += -Wno-multichar

LOCAL_PRELINK_MODULE:= false

LOCAL_MODULE:= libstagefright

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
endif
