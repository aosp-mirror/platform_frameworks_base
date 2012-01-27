LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
include frameworks/base/media/libstagefright/codecs/common/Config.mk



LOCAL_SRC_FILES := basic_op/basicop2.c basic_op/oper_32b.c

LOCAL_SRC_FILES += \
	AACEncoder.cpp \
	src/aac_rom.c \
	src/aacenc.c \
	src/aacenc_core.c \
	src/adj_thr.c \
	src/band_nrg.c \
	src/bit_cnt.c \
	src/bitbuffer.c \
	src/bitenc.c \
	src/block_switch.c \
	src/channel_map.c \
	src/dyn_bits.c \
	src/grp_data.c \
	src/interface.c \
	src/line_pe.c \
	src/ms_stereo.c \
	src/pre_echo_control.c \
	src/psy_configuration.c \
	src/psy_main.c \
	src/qc_main.c \
	src/quantize.c \
	src/sf_estim.c \
	src/spreading.c \
	src/stat_bits.c \
	src/tns.c \
	src/transform.c \
	src/memalign.c

ifeq ($(VOTT), v5)
LOCAL_SRC_FILES += \
	src/asm/ARMV5E/AutoCorrelation_v5.s \
	src/asm/ARMV5E/band_nrg_v5.s \
	src/asm/ARMV5E/CalcWindowEnergy_v5.s \
	src/asm/ARMV5E/PrePostMDCT_v5.s \
	src/asm/ARMV5E/R4R8First_v5.s \
	src/asm/ARMV5E/Radix4FFT_v5.s
endif

ifeq ($(VOTT), v7)
LOCAL_SRC_FILES += \
	src/asm/ARMV5E/AutoCorrelation_v5.s \
	src/asm/ARMV5E/band_nrg_v5.s \
	src/asm/ARMV5E/CalcWindowEnergy_v5.s \
	src/asm/ARMV7/PrePostMDCT_v7.s \
	src/asm/ARMV7/R4R8First_v7.s \
	src/asm/ARMV7/Radix4FFT_v7.s
endif

LOCAL_MODULE := libstagefright_aacenc

LOCAL_ARM_MODE := arm

LOCAL_STATIC_LIBRARIES :=

LOCAL_SHARED_LIBRARIES :=

LOCAL_C_INCLUDES := \
	frameworks/base/media/libstagefright/include \
	frameworks/base/media/libstagefright/codecs/common/include \
	frameworks/base/include \
	$(LOCAL_PATH)/src \
	$(LOCAL_PATH)/inc \
	$(LOCAL_PATH)/basic_op

LOCAL_CFLAGS := $(VO_CFLAGS)

ifeq ($(VOTT), v5)
LOCAL_CFLAGS += -DARMV5E -DARM_INASM -DARMV5_INASM
LOCAL_C_INCLUDES += $(LOCAL_PATH)/src/asm/ARMV5E
endif

ifeq ($(VOTT), v7)
LOCAL_CFLAGS += -DARMV5E -DARMV7Neon -DARM_INASM -DARMV5_INASM
LOCAL_C_INCLUDES += $(LOCAL_PATH)/src/asm/ARMV5E
LOCAL_C_INCLUDES += $(LOCAL_PATH)/src/asm/ARMV7
endif

include $(BUILD_STATIC_LIBRARY)

################################################################################

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
        SoftAACEncoder.cpp

LOCAL_C_INCLUDES := \
        frameworks/base/media/libstagefright/include \
        frameworks/base/include/media/stagefright/openmax \
	frameworks/base/media/libstagefright/codecs/common/include \

LOCAL_CFLAGS := -DOSCL_IMPORT_REF=

LOCAL_STATIC_LIBRARIES := \
        libstagefright_aacenc

LOCAL_SHARED_LIBRARIES := \
        libstagefright_omx libstagefright_foundation libutils \
        libstagefright_enc_common

LOCAL_MODULE := libstagefright_soft_aacenc
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)
