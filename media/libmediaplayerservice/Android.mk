LOCAL_PATH:= $(call my-dir)

#
# libmediaplayerservice
#

include $(CLEAR_VARS)

LOCAL_SRC_FILES:=               \
    MediaRecorderClient.cpp     \
    MediaPlayerService.cpp      \
    MetadataRetrieverClient.cpp \
    TestPlayerStub.cpp          \
    VorbisPlayer.cpp            \
    VorbisMetadataRetriever.cpp \
    MidiMetadataRetriever.cpp 	\
    MidiFile.cpp

ifeq ($(BUILD_WITH_FULL_STAGEFRIGHT),true)

LOCAL_SRC_FILES +=                      \
    StagefrightPlayer.cpp               \
    StagefrightRecorder.cpp

LOCAL_CFLAGS += -DBUILD_WITH_FULL_STAGEFRIGHT=1

endif

ifeq ($(TARGET_OS)-$(TARGET_SIMULATOR),linux-true)
LOCAL_LDLIBS += -ldl -lpthread
endif

LOCAL_SHARED_LIBRARIES :=     		\
	libcutils             			\
	libutils              			\
	libbinder             			\
	libvorbisidec         			\
	libsonivox            			\
	libmedia              			\
	libandroid_runtime    			\
	libstagefright        			\
	libstagefright_omx    			\
	libstagefright_color_conversion \
	libsurfaceflinger_client

ifneq ($(BUILD_WITHOUT_PV),true)
LOCAL_SHARED_LIBRARIES += \
	libopencore_player    \
	libopencore_author
else
LOCAL_CFLAGS += -DNO_OPENCORE
endif

ifneq ($(TARGET_SIMULATOR),true)
LOCAL_SHARED_LIBRARIES += libdl
endif

LOCAL_C_INCLUDES :=                                                 \
	$(JNI_H_INCLUDE)                                                \
	$(call include-path-for, graphics corecg)                       \
	$(TOP)/external/opencore/extern_libs_v2/khronos/openmax/include \
	$(TOP)/frameworks/base/media/libstagefright/include

ifeq ($(TARGET_ARCH),arm)
    LOCAL_C_INCLUDES += \
        $(TOP)/external/tremolo/Tremolo
else
    LOCAL_C_INCLUDES += \
        $(TOP)/external/tremor/Tremor
endif

LOCAL_MODULE:= libmediaplayerservice

include $(BUILD_SHARED_LIBRARY)

