LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    AudioParameter.cpp
LOCAL_MODULE:= libmedia_helper
LOCAL_MODULE_TAGS := optional

include $(BUILD_STATIC_LIBRARY)

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
    IMediaRecorderClient.cpp \
    IMediaPlayer.cpp \
    IMediaRecorder.cpp \
    IStreamSource.cpp \
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
    AudioEffect.cpp \
    Visualizer.cpp \
    MemoryLeakTrackUtil.cpp

LOCAL_SHARED_LIBRARIES := \
	libui libcutils libutils libbinder libsonivox libicuuc libexpat \
        libcamera_client libstagefright_foundation \
        libgui libdl libaudioutils

LOCAL_WHOLE_STATIC_LIBRARY := libmedia_helper

LOCAL_MODULE:= libmedia

LOCAL_C_INCLUDES := \
    $(JNI_H_INCLUDE) \
    $(call include-path-for, graphics corecg) \
    $(TOP)/frameworks/base/include/media/stagefright/openmax \
    external/icu4c/common \
    external/expat/lib \
    system/media/audio_effects/include \
    system/media/audio_utils/include

include $(BUILD_SHARED_LIBRARY)
