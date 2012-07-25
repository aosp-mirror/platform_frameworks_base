LOCAL_PATH:= $(call my-dir)
#
# libaah_rtp
#

include $(CLEAR_VARS)

LOCAL_MODULE := libaah_rtp
LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := \
    aah_audio_algorithm.cpp \
    aah_audio_processor.cpp \
    aah_decoder_pump.cpp \
    aah_rx_player.cpp \
    aah_rx_player_core.cpp \
    aah_rx_player_ring_buffer.cpp \
    aah_rx_player_substream.cpp \
    aah_tx_group.cpp \
    aah_tx_packet.cpp \
    aah_tx_player.cpp \
    AAHMetaDataService_jni.cpp \
    IAAHMetaData.cpp \
    utils.cpp

LOCAL_C_INCLUDES := \
    frameworks/base/include \
    frameworks/base/include/media/stagefright/openmax \
    frameworks/base/media \
    frameworks/base/media/libstagefright

LOCAL_SHARED_LIBRARIES := \
    libandroid_runtime \
    libbinder \
    libcommon_time_client \
    libcutils \
    libmedia \
    libstagefright \
    libstagefright_foundation \
    libutils

LOCAL_LDLIBS := \
    -lpthread

include $(BUILD_SHARED_LIBRARY)

include $(call all-makefiles-under,$(LOCAL_PATH))
