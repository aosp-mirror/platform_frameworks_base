LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
	src/pvmp3_normalize.cpp \
 	src/pvmp3_alias_reduction.cpp \
 	src/pvmp3_crc.cpp \
 	src/pvmp3_decode_header.cpp \
 	src/pvmp3_decode_huff_cw.cpp \
 	src/pvmp3_getbits.cpp \
 	src/pvmp3_dequantize_sample.cpp \
 	src/pvmp3_framedecoder.cpp \
 	src/pvmp3_get_main_data_size.cpp \
 	src/pvmp3_get_side_info.cpp \
 	src/pvmp3_get_scale_factors.cpp \
 	src/pvmp3_mpeg2_get_scale_data.cpp \
 	src/pvmp3_mpeg2_get_scale_factors.cpp \
 	src/pvmp3_mpeg2_stereo_proc.cpp \
 	src/pvmp3_huffman_decoding.cpp \
 	src/pvmp3_huffman_parsing.cpp \
 	src/pvmp3_tables.cpp \
 	src/pvmp3_imdct_synth.cpp \
 	src/pvmp3_mdct_6.cpp \
 	src/pvmp3_dct_6.cpp \
 	src/pvmp3_poly_phase_synthesis.cpp \
 	src/pvmp3_equalizer.cpp \
 	src/pvmp3_seek_synch.cpp \
 	src/pvmp3_stereo_proc.cpp \
 	src/pvmp3_reorder.cpp \

ifeq ($(TARGET_ARCH),arm)
LOCAL_SRC_FILES += \
	src/asm/pvmp3_polyphase_filter_window_gcc.s \
 	src/asm/pvmp3_mdct_18_gcc.s \
 	src/asm/pvmp3_dct_9_gcc.s \
	src/asm/pvmp3_dct_16_gcc.s
else
LOCAL_SRC_FILES += \
 	src/pvmp3_polyphase_filter_window.cpp \
 	src/pvmp3_mdct_18.cpp \
 	src/pvmp3_dct_9.cpp \
 	src/pvmp3_dct_16.cpp
endif

LOCAL_C_INCLUDES := \
        frameworks/base/media/libstagefright/include \
        $(LOCAL_PATH)/src \
        $(LOCAL_PATH)/include

LOCAL_CFLAGS := \
        -DOSCL_UNUSED_ARG=

LOCAL_MODULE := libstagefright_mp3dec

LOCAL_ARM_MODE := arm

include $(BUILD_STATIC_LIBRARY)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        SoftMP3.cpp

LOCAL_C_INCLUDES := \
        frameworks/base/media/libstagefright/include \
        frameworks/base/include/media/stagefright/openmax \
        $(LOCAL_PATH)/src \
        $(LOCAL_PATH)/include

LOCAL_SHARED_LIBRARIES := \
        libstagefright libstagefright_omx libstagefright_foundation libutils

LOCAL_STATIC_LIBRARIES := \
        libstagefright_mp3dec

LOCAL_MODULE := libstagefright_soft_mp3dec
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
