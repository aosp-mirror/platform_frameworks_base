LOCAL_PATH:= $(call my-dir)

# Music bundle

include $(CLEAR_VARS)

LOCAL_ARM_MODE := arm

LOCAL_SRC_FILES:= \
    StereoWidening/src/LVCS_BypassMix.c \
    StereoWidening/src/LVCS_Control.c \
    StereoWidening/src/LVCS_Equaliser.c \
    StereoWidening/src/LVCS_Init.c \
    StereoWidening/src/LVCS_Process.c \
    StereoWidening/src/LVCS_ReverbGenerator.c \
    StereoWidening/src/LVCS_StereoEnhancer.c \
    StereoWidening/src/LVCS_Tables.c \
    Bass/src/LVDBE_Control.c \
    Bass/src/LVDBE_Init.c \
    Bass/src/LVDBE_Process.c \
    Bass/src/LVDBE_Tables.c \
    Bundle/src/LVM_API_Specials.c \
    Bundle/src/LVM_Buffers.c \
    Bundle/src/LVM_Init.c \
    Bundle/src/LVM_Process.c \
    Bundle/src/LVM_Tables.c \
    Bundle/src/LVM_Control.c \
    SpectrumAnalyzer/src/LVPSA_Control.c \
    SpectrumAnalyzer/src/LVPSA_Init.c \
    SpectrumAnalyzer/src/LVPSA_Memory.c \
    SpectrumAnalyzer/src/LVPSA_Process.c \
    SpectrumAnalyzer/src/LVPSA_QPD_Init.c \
    SpectrumAnalyzer/src/LVPSA_QPD_Process.c \
    SpectrumAnalyzer/src/LVPSA_Tables.c \
    Eq/src/LVEQNB_CalcCoef.c \
    Eq/src/LVEQNB_Control.c \
    Eq/src/LVEQNB_Init.c \
    Eq/src/LVEQNB_Process.c \
    Eq/src/LVEQNB_Tables.c \
    Common/src/InstAlloc.c \
    Common/src/DC_2I_D16_TRC_WRA_01.c \
    Common/src/DC_2I_D16_TRC_WRA_01_Init.c \
    Common/src/FO_2I_D16F32C15_LShx_TRC_WRA_01.c \
    Common/src/FO_2I_D16F32Css_LShx_TRC_WRA_01_Init.c \
    Common/src/FO_1I_D16F16C15_TRC_WRA_01.c \
    Common/src/FO_1I_D16F16Css_TRC_WRA_01_Init.c \
    Common/src/BP_1I_D16F32C30_TRC_WRA_01.c \
    Common/src/BP_1I_D16F16C14_TRC_WRA_01.c \
    Common/src/BP_1I_D32F32C30_TRC_WRA_02.c \
    Common/src/BP_1I_D16F16Css_TRC_WRA_01_Init.c \
    Common/src/BP_1I_D16F32Cll_TRC_WRA_01_Init.c \
    Common/src/BP_1I_D32F32Cll_TRC_WRA_02_Init.c \
    Common/src/BQ_2I_D32F32Cll_TRC_WRA_01_Init.c \
    Common/src/BQ_2I_D32F32C30_TRC_WRA_01.c \
    Common/src/BQ_2I_D16F32C15_TRC_WRA_01.c \
    Common/src/BQ_2I_D16F32C14_TRC_WRA_01.c \
    Common/src/BQ_2I_D16F32C13_TRC_WRA_01.c \
    Common/src/BQ_2I_D16F32Css_TRC_WRA_01_init.c \
    Common/src/BQ_2I_D16F16C15_TRC_WRA_01.c \
    Common/src/BQ_2I_D16F16C14_TRC_WRA_01.c \
    Common/src/BQ_2I_D16F16Css_TRC_WRA_01_Init.c \
    Common/src/BQ_1I_D16F16C15_TRC_WRA_01.c \
    Common/src/BQ_1I_D16F16Css_TRC_WRA_01_Init.c \
    Common/src/BQ_1I_D16F32C14_TRC_WRA_01.c \
    Common/src/BQ_1I_D16F32Css_TRC_WRA_01_init.c \
    Common/src/PK_2I_D32F32C30G11_TRC_WRA_01.c \
    Common/src/PK_2I_D32F32C14G11_TRC_WRA_01.c \
    Common/src/PK_2I_D32F32CssGss_TRC_WRA_01_Init.c \
    Common/src/PK_2I_D32F32CllGss_TRC_WRA_01_Init.c \
    Common/src/Int16LShiftToInt32_16x32.c \
    Common/src/From2iToMono_16.c \
    Common/src/Copy_16.c \
    Common/src/MonoTo2I_16.c \
    Common/src/LoadConst_16.c \
    Common/src/dB_to_Lin32.c \
    Common/src/Shift_Sat_v16xv16.c \
    Common/src/Abs_32.c \
    Common/src/Int32RShiftToInt16_Sat_32x16.c \
    Common/src/From2iToMono_32.c \
    Common/src/mult3s_16x16.c \
    Common/src/NonLinComp_D16.c \
    Common/src/DelayMix_16x16.c \
    Common/src/MSTo2i_Sat_16x16.c \
    Common/src/From2iToMS_16x16.c \
    Common/src/Mac3s_Sat_16x16.c \
    Common/src/Add2_Sat_16x16.c \
    Common/src/LVC_MixSoft_1St_2i_D16C31_SAT.c \
    Common/src/LVC_MixSoft_1St_D16C31_SAT.c \
    Common/src/LVC_Mixer_VarSlope_SetTimeConstant.c \
    Common/src/LVC_Mixer_SetTimeConstant.c \
    Common/src/LVC_Mixer_SetTarget.c \
    Common/src/LVC_Mixer_GetTarget.c \
    Common/src/LVC_Mixer_Init.c \
    Common/src/LVC_Core_MixHard_1St_2i_D16C31_SAT.c \
    Common/src/LVC_Core_MixSoft_1St_2i_D16C31_WRA.c \
    Common/src/LVC_Core_MixInSoft_D16C31_SAT.c \
    Common/src/LVC_Mixer_GetCurrent.c \
    Common/src/LVC_MixSoft_2St_D16C31_SAT.c \
    Common/src/LVC_Core_MixSoft_1St_D16C31_WRA.c \
    Common/src/LVC_Core_MixHard_2St_D16C31_SAT.c \
    Common/src/LVC_MixInSoft_D16C31_SAT.c \
    Common/src/AGC_MIX_VOL_2St1Mon_D32_WRA.c \
    Common/src/LVM_Timer.c \
    Common/src/LVM_Timer_Init.c

LOCAL_MODULE:= libmusicbundle



LOCAL_C_INCLUDES += \
    $(LOCAL_PATH)/Eq/lib \
    $(LOCAL_PATH)/Eq/src \
    $(LOCAL_PATH)/Bass/lib \
    $(LOCAL_PATH)/Bass/src \
    $(LOCAL_PATH)/Common/lib \
    $(LOCAL_PATH)/Common/src \
    $(LOCAL_PATH)/Bundle/lib \
    $(LOCAL_PATH)/Bundle/src \
    $(LOCAL_PATH)/SpectrumAnalyzer/lib \
    $(LOCAL_PATH)/SpectrumAnalyzer/src \
    $(LOCAL_PATH)/StereoWidening/src \
    $(LOCAL_PATH)/StereoWidening/lib

include $(BUILD_STATIC_LIBRARY)

# Reverb library
include $(CLEAR_VARS)

LOCAL_ARM_MODE := arm

LOCAL_SRC_FILES:= \
    Reverb/src/LVREV_ApplyNewSettings.c \
    Reverb/src/LVREV_ClearAudioBuffers.c \
    Reverb/src/LVREV_GetControlParameters.c \
    Reverb/src/LVREV_GetInstanceHandle.c \
    Reverb/src/LVREV_GetMemoryTable.c \
    Reverb/src/LVREV_Process.c \
    Reverb/src/LVREV_SetControlParameters.c \
    Reverb/src/LVREV_Tables.c \
    Common/src/Abs_32.c \
    Common/src/InstAlloc.c \
    Common/src/LoadConst_16.c \
    Common/src/LoadConst_32.c \
    Common/src/From2iToMono_32.c \
    Common/src/Mult3s_32x16.c \
    Common/src/FO_1I_D32F32C31_TRC_WRA_01.c \
    Common/src/FO_1I_D32F32Cll_TRC_WRA_01_Init.c \
    Common/src/DelayAllPass_Sat_32x16To32.c \
    Common/src/Copy_16.c \
    Common/src/Mac3s_Sat_32x16.c \
    Common/src/DelayWrite_32.c \
    Common/src/Shift_Sat_v32xv32.c \
    Common/src/Add2_Sat_32x32.c \
    Common/src/JoinTo2i_32x32.c \
    Common/src/MonoTo2I_32.c \
    Common/src/LVM_FO_HPF.c \
    Common/src/LVM_FO_LPF.c \
    Common/src/LVM_Polynomial.c \
    Common/src/LVM_Power10.c \
    Common/src/LVM_GetOmega.c \
    Common/src/MixSoft_2St_D32C31_SAT.c \
    Common/src/MixSoft_1St_D32C31_WRA.c \
    Common/src/MixInSoft_D32C31_SAT.c \
    Common/src/LVM_Mixer_TimeConstant.c \
    Common/src/Core_MixHard_2St_D32C31_SAT.c \
    Common/src/Core_MixSoft_1St_D32C31_WRA.c \
    Common/src/Core_MixInSoft_D32C31_SAT.c

LOCAL_MODULE:= libreverb



LOCAL_C_INCLUDES += \
    $(LOCAL_PATH)/Reverb/lib \
    $(LOCAL_PATH)/Reverb/src \
    $(LOCAL_PATH)/Common/lib \
    $(LOCAL_PATH)/Common/src

include $(BUILD_STATIC_LIBRARY)
