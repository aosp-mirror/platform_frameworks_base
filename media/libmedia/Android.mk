LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
	AudioTrack.cpp \
	IAudioFlinger.cpp \
	IAudioTrack.cpp \
	IAudioRecord.cpp \
	AudioRecord.cpp \
	AudioSystem.cpp \
	mediaplayer.cpp \
	IMediaPlayerService.cpp \
	IMediaPlayerClient.cpp \
	IMediaPlayer.cpp \
	IMediaRecorder.cpp \
	mediarecorder.cpp \
	IMediaMetadataRetriever.cpp \
	mediametadataretriever.cpp \
	ToneGenerator.cpp

LOCAL_SHARED_LIBRARIES := \
	libui libcutils libutils

LOCAL_MODULE:= libmedia

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
LOCAL_LDLIBS += -ldl
endif

ifneq ($(TARGET_SIMULATOR),true)
LOCAL_SHARED_LIBRARIES += libdl
endif

LOCAL_C_INCLUDES := \
	$(call include-path-for, graphics corecg)

include $(BUILD_SHARED_LIBRARY)
