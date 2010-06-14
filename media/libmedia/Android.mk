LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    AudioTrack.cpp \
    IAudioFlinger.cpp \
    IAudioFlingerClient.cpp \
    IAudioTrack.cpp \
    IAudioRecord.cpp \
    AudioRecord.cpp \
    AudioSystem.cpp \
    mediaplayer.cpp \
    IMediaPlayerService.cpp \
    IMediaPlayerClient.cpp \
    IMediaPlayer.cpp \
    IMediaRecorder.cpp \
    Metadata.cpp \
    mediarecorder.cpp \
    IMediaMetadataRetriever.cpp \
    mediametadataretriever.cpp \
    ToneGenerator.cpp \
    JetPlayer.cpp \
    IOMX.cpp \
    IAudioPolicyService.cpp \
    MediaScanner.cpp \
    MediaScannerClient.cpp \
    autodetect.cpp \
    IMediaDeathNotifier.cpp \
    MediaProfiles.cpp \
    IEffect.cpp \
    IEffectClient.cpp \
    AudioEffect.cpp

LOCAL_SHARED_LIBRARIES := \
	libui libcutils libutils libbinder libsonivox libicuuc libexpat libsurfaceflinger_client libcamera_client

LOCAL_MODULE:= libmedia

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
LOCAL_LDLIBS += -ldl -lpthread
endif

ifneq ($(TARGET_SIMULATOR),true)
LOCAL_SHARED_LIBRARIES += libdl
endif

LOCAL_C_INCLUDES := \
    $(JNI_H_INCLUDE) \
    $(call include-path-for, graphics corecg) \
    $(TOP)/external/opencore/extern_libs_v2/khronos/openmax/include \
    external/speex/include \
    external/speex/libspeex \
    external/icu4c/common \
    external/expat/lib

LOCAL_STATIC_LIBRARIES := libspeex

include $(BUILD_SHARED_LIBRARY)
