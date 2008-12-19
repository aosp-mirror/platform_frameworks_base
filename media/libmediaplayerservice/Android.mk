LOCAL_PATH:= $(call my-dir)

#
# libmediaplayerservice
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
	MediaRecorderClient.cpp \
	MediaPlayerService.cpp \
	MetadataRetrieverClient.cpp \
	VorbisPlayer.cpp \
	MidiFile.cpp

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
LOCAL_LDLIBS += -ldl -lpthread
endif

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libvorbisidec \
	libsonivox \
	libopencoreplayer \
	libopencoreauthor \
	libmedia \
	libandroid_runtime

LOCAL_C_INCLUDES := external/tremor/Tremor \
	$(call include-path-for, graphics corecg)

LOCAL_MODULE:= libmediaplayerservice

include $(BUILD_SHARED_LIBRARY)

