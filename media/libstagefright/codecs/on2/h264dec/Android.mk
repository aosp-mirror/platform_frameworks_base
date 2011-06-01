LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_ARM_MODE := arm

LOCAL_SRC_FILES := \
	./source/h264bsd_transform.c \
	./source/h264bsd_util.c \
	./source/h264bsd_byte_stream.c \
	./source/h264bsd_seq_param_set.c \
	./source/h264bsd_pic_param_set.c \
	./source/h264bsd_slice_header.c \
	./source/h264bsd_slice_data.c \
	./source/h264bsd_macroblock_layer.c \
	./source/h264bsd_stream.c \
	./source/h264bsd_vlc.c \
	./source/h264bsd_cavlc.c \
	./source/h264bsd_nal_unit.c \
	./source/h264bsd_neighbour.c \
	./source/h264bsd_storage.c \
	./source/h264bsd_slice_group_map.c \
	./source/h264bsd_intra_prediction.c \
	./source/h264bsd_inter_prediction.c \
	./source/h264bsd_reconstruct.c \
	./source/h264bsd_dpb.c \
	./source/h264bsd_image.c \
	./source/h264bsd_deblocking.c \
	./source/h264bsd_conceal.c \
	./source/h264bsd_vui.c \
	./source/h264bsd_pic_order_cnt.c \
	./source/h264bsd_decoder.c \
	./source/H264SwDecApi.c \
	SoftAVC.cpp \

LOCAL_C_INCLUDES := $(LOCAL_PATH)/./inc \
	frameworks/base/media/libstagefright/include \
	frameworks/base/include/media/stagefright/openmax \

MY_ASM := \
	./source/arm_neon_asm_gcc/h264bsdWriteMacroblock.S \
	./source/arm_neon_asm_gcc/h264bsdClearMbLayer.S \
	./source/arm_neon_asm_gcc/h264bsdFillRow7.S \
	./source/arm_neon_asm_gcc/h264bsdCountLeadingZeros.S \
	./source/arm_neon_asm_gcc/h264bsdFlushBits.S


MY_OMXDL_C_SRC := \
	./omxdl/arm_neon/vc/m4p10/src/omxVCM4P10_DeblockChroma_I.c \
	./omxdl/arm_neon/vc/m4p10/src/omxVCM4P10_DeblockLuma_I.c \
	./omxdl/arm_neon/vc/m4p10/src/omxVCM4P10_InterpolateChroma.c \
	./omxdl/arm_neon/vc/m4p10/src/armVCM4P10_CAVLCTables.c \
	./omxdl/arm_neon/vc/m4p10/src/omxVCM4P10_DecodeChromaDcCoeffsToPairCAVLC.c \
	./omxdl/arm_neon/vc/m4p10/src/omxVCM4P10_DecodeCoeffsToPairCAVLC.c \
	./omxdl/arm_neon/src/armCOMM_Bitstream.c \
	./omxdl/arm_neon/src/armCOMM.c

MY_OMXDL_ASM_SRC := \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_DeblockingChroma_unsafe_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_DeblockingLuma_unsafe_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_Interpolate_Chroma_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_InterpolateLuma_Align_unsafe_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_InterpolateLuma_Copy_unsafe_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_InterpolateLuma_DiagCopy_unsafe_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_InterpolateLuma_HalfDiagHorVer4x4_unsafe_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_InterpolateLuma_HalfDiagVerHor4x4_unsafe_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_InterpolateLuma_HalfHor4x4_unsafe_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_InterpolateLuma_HalfVer4x4_unsafe_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/omxVCM4P10_FilterDeblockingChroma_HorEdge_I_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/omxVCM4P10_FilterDeblockingChroma_VerEdge_I_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/omxVCM4P10_FilterDeblockingLuma_HorEdge_I_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/omxVCM4P10_FilterDeblockingLuma_VerEdge_I_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/omxVCM4P10_InterpolateLuma_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_Average_4x_Align_unsafe_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_DecodeCoeffsToPair_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_DequantTables_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_QuantTables_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_TransformResidual4x4_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/armVCM4P10_UnpackBlock4x4_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/omxVCM4P10_TransformDequantLumaDCFromPair_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/omxVCM4P10_PredictIntra_16x16_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/omxVCM4P10_PredictIntra_4x4_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/omxVCM4P10_PredictIntraChroma_8x8_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/omxVCM4P10_DequantTransformResidualFromPairAndAdd_s.S \
	./omxdl/arm_neon/vc/m4p10/src_gcc/omxVCM4P10_TransformDequantChromaDCFromPair_s.S \


ifeq ($(ARCH_ARM_HAVE_NEON),true)
    LOCAL_ARM_NEON   := true
#    LOCAL_CFLAGS     := -std=c99 -D._NEON -D._OMXDL
    LOCAL_CFLAGS     := -DH264DEC_NEON -DH264DEC_OMXDL
    LOCAL_SRC_FILES  += $(MY_ASM) $(MY_OMXDL_C_SRC) $(MY_OMXDL_ASM_SRC)
    LOCAL_C_INCLUDES += $(LOCAL_PATH)/./source/arm_neon_asm_gcc
    LOCAL_C_INCLUDES += $(LOCAL_PATH)/./omxdl/arm_neon/api \
                        $(LOCAL_PATH)/./omxdl/arm_neon/vc/api \
                        $(LOCAL_PATH)/./omxdl/arm_neon/vc/m4p10/api
endif

LOCAL_SHARED_LIBRARIES := \
	libstagefright libstagefright_omx libstagefright_foundation libutils \

LOCAL_MODULE := libstagefright_soft_h264dec

LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)

#####################################################################
# test utility: decoder
#####################################################################
##
## Test application
##
include $(CLEAR_VARS)

LOCAL_SRC_FILES := ./source/DecTestBench.c

LOCAL_C_INCLUDES := $(LOCAL_PATH)/inc

LOCAL_SHARED_LIBRARIES := libstagefright_soft_h264dec

LOCAL_MODULE_TAGS := debug

LOCAL_MODULE := decoder

include $(BUILD_EXECUTABLE)

