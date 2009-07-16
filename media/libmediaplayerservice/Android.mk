LOCAL_PATH:= $(call my-dir)

#
# libmediaplayerservice
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    MediaRecorderClient.cpp \
    MediaPlayerService.cpp \
    MetadataRetrieverClient.cpp \
    TestPlayerStub.cpp \
    VorbisPlayer.cpp \
    MidiFile.cpp

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
LOCAL_LDLIBS += -ldl -lpthread
endif

LOCAL_SHARED_LIBRARIES := \
    libcutils \
    libutils \
	libbinder \
    libvorbisidec \
    libsonivox \
    libopencore_player \
    libopencore_author \
    libmedia \
    libandroid_runtime

ifneq ($(TARGET_SIMULATOR),true)
LOCAL_SHARED_LIBRARIES += libdl
endif

LOCAL_C_INCLUDES := external/tremor/Tremor \
	$(call include-path-for, graphics corecg) \
	$(TOP)/external/opencore/extern_libs_v2/khronos/openmax/include

LOCAL_MODULE:= libmediaplayerservice

ifeq ($(BUILD_WITH_STAGEFRIGHT),true)
    LOCAL_SRC_FILES += StagefrightPlayer.cpp

    LOCAL_SHARED_LIBRARIES += \
	libstagefright        \
	libstagefright_omx

    LOCAL_C_INCLUDES += $(TOP)/frameworks/base/media/libstagefright/omx

    LOCAL_CFLAGS += -DBUILD_WITH_STAGEFRIGHT -DUSE_STAGEFRIGHT
endif

include $(BUILD_SHARED_LIBRARY)

