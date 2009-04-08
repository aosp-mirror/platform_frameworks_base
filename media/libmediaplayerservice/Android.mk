LOCAL_PATH:= $(call my-dir)

#
# libmediaplayerservice
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
	MediaPlayerService.cpp \
	MetadataRetrieverClient.cpp \
	VorbisPlayer.cpp \
	MidiFile.cpp

ifneq ($(BUILD_WITHOUT_PV),true)
LOCAL_SRC_FILES+=               \
	MediaRecorderClient.cpp
endif

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
LOCAL_LDLIBS += -ldl -lpthread
endif

LOCAL_SHARED_LIBRARIES := \
	libcutils \
	libutils \
	libvorbisidec \
	libsonivox \
	libmedia \
	libandroid_runtime

ifneq ($(BUILD_WITHOUT_PV),true)
LOCAL_SHARED_LIBRARIES += \
	libopencoreplayer \
	libopencoreauthor
endif

LOCAL_C_INCLUDES := external/tremor/Tremor \
	$(call include-path-for, graphics corecg)

ifeq ($(BUILD_WITHOUT_PV),true)
LOCAL_CFLAGS := -DNO_OPENCORE
endif

LOCAL_MODULE:= libmediaplayerservice

include $(BUILD_SHARED_LIBRARY)

