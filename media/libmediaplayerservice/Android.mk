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
    libopencore_player \
    libopencore_author \
    libmedia \
    libandroid_runtime

ifneq ($(BUILD_WITHOUT_PV),true)
LOCAL_SHARED_LIBRARIES += \
	libopencore_player \
	libopencore_author
endif

LOCAL_C_INCLUDES := external/tremor/Tremor \
    $(call include-path-for, graphics corecg)

ifeq ($(BUILD_WITHOUT_PV),true)
LOCAL_CFLAGS := -DNO_OPENCORE
endif

LOCAL_MODULE:= libmediaplayerservice

include $(BUILD_SHARED_LIBRARY)

