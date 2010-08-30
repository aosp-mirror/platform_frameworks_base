/*
 * Copyright (C) 2004-2010 NXP Software
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/****************************************************************************************/
/*                                                                                      */
/*    Includes                                                                          */
/*                                                                                      */
/****************************************************************************************/

#include "LVDBE.h"
#include "LVDBE_Private.h"
#include "VectorArithmetic.h"
#include "LVDBE_Coeffs.h"
#include "LVDBE_Tables.h"

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                  LVDBE_GetParameters                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    Request the Dynamic Bass Enhancement parameters. The current parameter set is     */
/*  returned via the parameter pointer.                                                 */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance                   Instance handle                                         */
/*  pParams                  Pointer to an empty parameter structure                    */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVDBE_SUCCESS            Always succeeds                                            */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.    This function may be interrupted by the LVDBE_Process function                */
/*                                                                                      */
/****************************************************************************************/

LVDBE_ReturnStatus_en LVDBE_GetParameters(LVDBE_Handle_t        hInstance,
                                            LVDBE_Params_t        *pParams)
{

    LVDBE_Instance_t    *pInstance =(LVDBE_Instance_t  *)hInstance;

    *pParams = pInstance->Params;

    return(LVDBE_SUCCESS);
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                  LVDBE_GetCapabilities                                 */
/*                                                                                  */
/* DESCRIPTION: Dynamic Bass Enhnacement capabilities. The current capabilities are */
/* returned via the pointer.                                                        */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance                   Instance handle                                     */
/*  pCapabilities              Pointer to an empty capability structure             */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVDBE_Success             Always succeeds                                       */
/*                                                                                  */
/* NOTES:                                                                           */
/*  1.    This function may be interrupted by the LVDBE_Process function            */
/*                                                                                  */
/************************************************************************************/

LVDBE_ReturnStatus_en LVDBE_GetCapabilities(LVDBE_Handle_t            hInstance,
                                              LVDBE_Capabilities_t    *pCapabilities)
{

    LVDBE_Instance_t    *pInstance =(LVDBE_Instance_t  *)hInstance;

    *pCapabilities = pInstance->Capabilities;

    return(LVDBE_SUCCESS);
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVDBE_SetFilters                                            */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Sets the filter coefficients and clears the data history                        */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInstance           Pointer to the instance                                     */
/*  pParams             Initialisation parameters                                   */
/*                                                                                  */
/************************************************************************************/

void    LVDBE_SetFilters(LVDBE_Instance_t     *pInstance,
                         LVDBE_Params_t       *pParams)
{

    /*
     * Calculate the table offsets
     */
    LVM_UINT16 Offset = (LVM_UINT16)((LVM_UINT16)pParams->SampleRate + (LVM_UINT16)(pParams->CentreFrequency * (1+LVDBE_FS_48000)));


    /*
     * Setup the high pass filter
     */
    LoadConst_16(0,                                                 /* Clear the history, value 0 */
                 (void *)&pInstance->pData->HPFTaps,                /* Destination Cast to void: \
                                                                     no dereferencing in function*/
                 sizeof(pInstance->pData->HPFTaps)/sizeof(LVM_INT16));   /* Number of words */
    BQ_2I_D32F32Cll_TRC_WRA_01_Init(&pInstance->pCoef->HPFInstance,         /* Initialise the filter */
                                    &pInstance->pData->HPFTaps,
                                    (BQ_C32_Coefs_t *)&LVDBE_HPF_Table[Offset]);


    /*
     * Setup the band pass filter
     */
    LoadConst_16(0,                                                 /* Clear the history, value 0 */
                 (void *)&pInstance->pData->BPFTaps,                /* Destination Cast to void:\
                                                                     no dereferencing in function*/
                 sizeof(pInstance->pData->BPFTaps)/sizeof(LVM_INT16));   /* Number of words */
    BP_1I_D32F32Cll_TRC_WRA_02_Init(&pInstance->pCoef->BPFInstance,         /* Initialise the filter */
                                    &pInstance->pData->BPFTaps,
                                    (BP_C32_Coefs_t *)&LVDBE_BPF_Table[Offset]);

}



/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVDBE_SetAGC                                                */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Sets the AGC gain level and attack and decay times constants.                   */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInstance           Pointer to the instance                                     */
/*  pParams             Initialisation parameters                                   */
/*                                                                                  */
/************************************************************************************/

void    LVDBE_SetAGC(LVDBE_Instance_t     *pInstance,
                     LVDBE_Params_t       *pParams)
{

    /*
     * Get the attack and decay time constants
     */
    pInstance->pData->AGCInstance.AGC_Attack = LVDBE_AGC_ATTACK_Table[(LVM_UINT16)pParams->SampleRate];  /* Attack multiplier */
    pInstance->pData->AGCInstance.AGC_Decay  = LVDBE_AGC_DECAY_Table[(LVM_UINT16)pParams->SampleRate];   /* Decay multipler */


    /*
     * Get the boost gain
     */
    if (pParams->HPFSelect == LVDBE_HPF_ON)
    {
        pInstance->pData->AGCInstance.AGC_MaxGain   = LVDBE_AGC_HPFGAIN_Table[(LVM_UINT16)pParams->EffectLevel];  /* High pass filter on */
    }
    else
    {
        pInstance->pData->AGCInstance.AGC_MaxGain   = LVDBE_AGC_GAIN_Table[(LVM_UINT16)pParams->EffectLevel];     /* High pass filter off */
    }
    pInstance->pData->AGCInstance.AGC_GainShift = AGC_GAIN_SHIFT;
    pInstance->pData->AGCInstance.AGC_Target = AGC_TARGETLEVEL;

}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVDBE_SetVolume                                             */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Converts the input volume demand from dBs to linear.                            */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInstance           Pointer to the instance                                     */
/*  pParams             Initialisation parameters                                   */
/*                                                                                  */
/* NOTES:                                                                           */
/*  1. The volume should have the following settings:                               */
/*                                                                                  */
/*          DBE         Vol Control           Volume setting                        */
/*          ===         ===========         ===================                     */
/*          Off             Off                 HeadroomdB                          */
/*          Off             On              VolumedB+HeadroomdB                     */
/*          On              Off                 HeadroomdB                          */
/*          On              On              VolumedB+HeadroomdB                     */
/*                                                                                  */
/************************************************************************************/

void    LVDBE_SetVolume(LVDBE_Instance_t     *pInstance,
                        LVDBE_Params_t       *pParams)
{

    LVM_UINT16      dBShifts;                                   /* 6dB shifts */
    LVM_UINT16      dBOffset;                                   /* Table offset */
    LVM_INT16       Volume = 0;                                 /* Required volume in dBs */

    /*
     * Apply the volume if enabled
     */
    if (pParams->VolumeControl == LVDBE_VOLUME_ON)
    {
        /*
         * Limit the gain to the maximum allowed
         */
        if  (pParams->VolumedB > VOLUME_MAX)
        {
            Volume = VOLUME_MAX;
        }
        else
        {
            Volume = pParams->VolumedB;
        }
    }


    /*
     * Calculate the required gain and shifts
     */
    dBOffset = (LVM_UINT16)(6 + Volume % 6);                    /* Get the dBs 0-5 */
    dBShifts = (LVM_UINT16)(Volume / -6);                       /* Get the 6dB shifts */


    /*
     * When DBE is enabled use AGC volume
     */
    pInstance->pData->AGCInstance.Target = ((LVM_INT32)LVDBE_VolumeTable[dBOffset] << 16);
    pInstance->pData->AGCInstance.Target = pInstance->pData->AGCInstance.Target >> dBShifts;

    pInstance->pData->AGCInstance.VolumeTC    = LVDBE_VolumeTCTable[(LVM_UINT16)pParams->SampleRate];   /* Volume update time constant */
    pInstance->pData->AGCInstance.VolumeShift = VOLUME_SHIFT+1;

    /*
     * When DBE is disabled use the bypass volume control
     */
    if(dBShifts > 0)
    {
        LVC_Mixer_SetTarget(&pInstance->pData->BypassVolume.MixerStream[0],(((LVM_INT32)LVDBE_VolumeTable[dBOffset]) >> dBShifts));
    }
    else
    {
        LVC_Mixer_SetTarget(&pInstance->pData->BypassVolume.MixerStream[0],(LVM_INT32)LVDBE_VolumeTable[dBOffset]);
    }

    pInstance->pData->BypassVolume.MixerStream[0].CallbackSet = 1;
    LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->pData->BypassVolume.MixerStream[0],
                                LVDBE_MIXER_TC,
                                (LVM_Fs_en)pInstance->Params.SampleRate,
                                2);
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVDBE_Control                                               */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Sets or changes the Bass Enhancement parameters. Changing the parameters while the  */
/*  module is processing signals may have the following side effects:                   */
/*                                                                                      */
/*  General parameters:                                                                 */
/*  ===================                                                                 */
/*  OperatingMode:      Changing the mode of operation may cause a change in volume     */
/*                      level or cause pops and clicks.                                 */
/*                                                                                      */
/*  SampleRate:         Changing the sample rate may cause pops and clicks.             */
/*                                                                                      */
/*  EffectLevel:        Changing the effect level may cause pops and clicks             */
/*                                                                                      */
/*  CentreFrequency:    Changing the centre frequency may cause pops and clicks         */
/*                                                                                      */
/*  HPFSelect:          Selecting/de-selecting the high pass filter may cause pops and  */
/*                      clicks                                                          */
/*                                                                                      */
/*  VolumedB            Changing the volume setting will have no side effects           */
/*                                                                                      */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pParams                 Pointer to a parameter structure                            */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVDBE_SUCCESS           Always succeeds                                             */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function must not be interrupted by the LVDBE_Process function             */
/*                                                                                      */
/****************************************************************************************/

LVDBE_ReturnStatus_en LVDBE_Control(LVDBE_Handle_t         hInstance,
                                      LVDBE_Params_t         *pParams)
{

    LVDBE_Instance_t    *pInstance =(LVDBE_Instance_t  *)hInstance;
    LVMixer3_2St_st     *pBypassMixer_Instance = &pInstance->pData->BypassMixer;


    /*
     * Update the filters
     */
    if ((pInstance->Params.SampleRate != pParams->SampleRate) ||
        (pInstance->Params.CentreFrequency != pParams->CentreFrequency))
    {
        LVDBE_SetFilters(pInstance,                     /* Instance pointer */
                         pParams);                      /* New parameters */
    }


    /*
     * Update the AGC is the effect level has changed
     */
    if ((pInstance->Params.SampleRate != pParams->SampleRate) ||
        (pInstance->Params.EffectLevel != pParams->EffectLevel) ||
        (pInstance->Params.HPFSelect != pParams->HPFSelect))
    {
        LVDBE_SetAGC(pInstance,                         /* Instance pointer */
                     pParams);                          /* New parameters */

        LVC_Mixer_SetTimeConstant(&pBypassMixer_Instance->MixerStream[0],
            LVDBE_BYPASS_MIXER_TC,pParams->SampleRate,2);

        LVC_Mixer_SetTimeConstant(&pBypassMixer_Instance->MixerStream[1],
            LVDBE_BYPASS_MIXER_TC,pParams->SampleRate,2);


    }


    /*
     * Update the Volume if the volume demand has changed
     */
    if ((pInstance->Params.VolumedB != pParams->VolumedB) ||
        (pInstance->Params.SampleRate != pParams->SampleRate) ||
        (pInstance->Params.HeadroomdB != pParams->HeadroomdB) ||
        (pInstance->Params.VolumeControl != pParams->VolumeControl))
    {
        LVDBE_SetVolume(pInstance,                      /* Instance pointer */
                       pParams);                        /* New parameters */
    }

    if (pInstance->Params.OperatingMode==LVDBE_ON && pParams->OperatingMode==LVDBE_OFF)
    {
        LVC_Mixer_SetTarget(&pInstance->pData->BypassMixer.MixerStream[0],0);
        LVC_Mixer_SetTarget(&pInstance->pData->BypassMixer.MixerStream[1],0x00007FFF);
    }
    if (pInstance->Params.OperatingMode==LVDBE_OFF && pParams->OperatingMode==LVDBE_ON)
    {
        LVC_Mixer_SetTarget(&pInstance->pData->BypassMixer.MixerStream[0],0x00007FFF);
        LVC_Mixer_SetTarget(&pInstance->pData->BypassMixer.MixerStream[1],0);
    }

    /*
     * Update the instance parameters
     */
    pInstance->Params = *pParams;


    return(LVDBE_SUCCESS);
}
