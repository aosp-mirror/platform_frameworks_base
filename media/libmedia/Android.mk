LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    autodetect.cpp \
    IMediaDeathNotifier.cpp \
    IMediaMetadataRetriever.cpp \
    IMediaPlayerClient.cpp \
    IMediaPlayer.cpp \
    IMediaPlayerService.cpp \
    IMediaRecorderClient.cpp \
    IMediaRecorder.cpp \
    IOMX.cpp \
    IStreamSource.cpp \
    JetPlayer.cpp \
    mediametadataretriever.cpp \
    mediaplayer.cpp \
    MediaProfiles.cpp \
    mediarecorder.cpp \
    MediaScannerClient.cpp \
    MediaScanner.cpp \
    MemoryLeakTrackUtil.cpp \
    Metadata.cpp \
    Visualizer.cpp

LOCAL_SHARED_LIBRARIES := \
	libui libcutils libutils libbinder libsonivox libicuuc libexpat \
        libcamera_client libstagefright_foundation \
        libgui libdl libaudioutils libmedia_native

LOCAL_WHOLE_STATIC_LIBRARY := libmedia_helper

LOCAL_MODULE:= libmedia

LOCAL_C_INCLUDES := \
    $(JNI_H_INCLUDE) \
    $(call include-path-for, graphics corecg) \
    $(TOP)/frameworks/native/include/media/openmax \
    external/icu4c/common \
    external/expat/lib \
    $(call include-path-for, audio-effects) \
    $(call include-path-for, audio-utils)

include $(BUILD_SHARED_LIBRARY)
