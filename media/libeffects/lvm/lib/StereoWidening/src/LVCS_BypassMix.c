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

/************************************************************************************/
/*                                                                                  */
/*  Includes                                                                        */
/*                                                                                  */
/************************************************************************************/

#include "LVCS.h"
#include "LVCS_Private.h"
#include "LVCS_BypassMix.h"
#include "VectorArithmetic.h"
#include "LVCS_Tables.h"

/****************************************************************************************/
/*                                                                                      */
/*  Function Prototypes                                                                 */
/*                                                                                      */
/****************************************************************************************/
LVM_INT32 LVCS_MixerCallback(   LVCS_Handle_t   hInstance,
                                void            *pGeneralPurpose,
                                LVM_INT16       CallbackParam);

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                LVCS_BypassMixInit                                      */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Initialises the bypass mixer module                                             */
/*                                                                                  */
/*  The overall gain of the processed path is set by the gains in the individual    */
/*  processing blocks and by the effect level gain.                                 */
/*                                                                                  */
/*  The unprocessed path must have matching gain for the processed path to ensure   */
/*  as they are mixed together the correct effect is achieved, this is the value    */
/*  UnprocLoss.                                                                     */
/*                                                                                  */
/*  The overall gain is corrected by a combination of a shift with saturation and a */
/*  linear scaler, loss. The loss ensures the sum in the mixer does not saturate    */
/*  and also corrects for any excess gain in the shift.                             */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance               Instance Handle                                         */
/*  pParams                 Initialisation parameters                               */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVCS_Success            Always succeeds                                         */
/*                                                                                  */
/* NOTES:                                                                           */
/*                                                                                  */
/************************************************************************************/

LVCS_ReturnStatus_en LVCS_BypassMixInit(LVCS_Handle_t       hInstance,
                                        LVCS_Params_t       *pParams)
{

    LVM_UINT16          Offset;
    LVM_UINT32          Gain;
    LVCS_Instance_t     *pInstance = (LVCS_Instance_t  *)hInstance;
    LVCS_BypassMix_t    *pConfig   = (LVCS_BypassMix_t *)&pInstance->BypassMix;
    const Gain_t        *pOutputGainTable;
    LVM_INT32           Current;


    /*
     * Set the transition gain
     */
    if ((pParams->OperatingMode == LVCS_ON) &&
        (pInstance->bTimerDone == LVM_TRUE)
        && (pInstance->MSTarget1 != 0x7FFF) /* this indicates an off->on transtion */
        )
    {
        pInstance->TransitionGain = pParams->EffectLevel;
    }
    else
    {
        /* Select no effect level */
        pInstance->TransitionGain = 0;
    }

    /*
     * Calculate the output gain table offset
     */
    Offset = (LVM_UINT16)(pParams->SpeakerType + (pParams->SourceFormat*(1+LVCS_EX_HEADPHONES)));
    pOutputGainTable = (Gain_t*)&LVCS_OutputGainTable[0];

    /*
     * Setup the mixer gain for the processed path
     */
    Gain = (LVM_UINT32)(pOutputGainTable[Offset].Loss * pInstance->TransitionGain);

    pConfig->Mixer_Instance.MixerStream[0].CallbackParam = 0;
    pConfig->Mixer_Instance.MixerStream[0].pCallbackHandle = LVM_NULL;
    pConfig->Mixer_Instance.MixerStream[0].pCallBack = LVM_NULL;
    pConfig->Mixer_Instance.MixerStream[0].CallbackSet=1;
    Current = LVC_Mixer_GetCurrent(&pConfig->Mixer_Instance.MixerStream[0]);
    LVC_Mixer_Init(&pConfig->Mixer_Instance.MixerStream[0],(LVM_INT32)(Gain >> 15),Current);
    LVC_Mixer_VarSlope_SetTimeConstant(&pConfig->Mixer_Instance.MixerStream[0],LVCS_BYPASS_MIXER_TC,pParams->SampleRate,2);
    /*
     * Setup the mixer gain for the unprocessed path
     */
    Gain = (LVM_UINT32)(pOutputGainTable[Offset].Loss * (0x7FFF - pInstance->TransitionGain));
    Gain = (LVM_UINT32)pOutputGainTable[Offset].UnprocLoss * (Gain >> 15);
    Current = LVC_Mixer_GetCurrent(&pConfig->Mixer_Instance.MixerStream[1]);
    LVC_Mixer_Init(&pConfig->Mixer_Instance.MixerStream[1],(LVM_INT32)(Gain >> 15),Current);
    LVC_Mixer_VarSlope_SetTimeConstant(&pConfig->Mixer_Instance.MixerStream[1],LVCS_BYPASS_MIXER_TC,pParams->SampleRate,2);
    pConfig->Mixer_Instance.MixerStream[1].CallbackParam = 0;
    pConfig->Mixer_Instance.MixerStream[1].pCallbackHandle = hInstance;
    pConfig->Mixer_Instance.MixerStream[1].CallbackSet=1;
    pConfig->Mixer_Instance.MixerStream[1].pCallBack = LVCS_MixerCallback;

    /*
     * Setup the output gain shift
     */
    pConfig->Output_Shift = pOutputGainTable[Offset].Shift;


    /*
     * Correct gain for the effect level
     */
    {

        LVM_INT16           GainCorrect;
        LVM_INT32           Gain1;
        LVM_INT32           Gain2;

        Gain1 = LVC_Mixer_GetTarget(&pConfig->Mixer_Instance.MixerStream[0]);
        Gain2 = LVC_Mixer_GetTarget(&pConfig->Mixer_Instance.MixerStream[1]);
        /*
         * Calculate the gain correction
         */
        if (pInstance->Params.CompressorMode == LVM_MODE_ON)
        {
        GainCorrect = (LVM_INT16)(  pInstance->VolCorrect.GainMin
                                    - (((LVM_INT32)pInstance->VolCorrect.GainMin * (LVM_INT32)pInstance->TransitionGain) >> 15)
                                    + (((LVM_INT32)pInstance->VolCorrect.GainFull * (LVM_INT32)pInstance->TransitionGain) >> 15) );

        /*
         * Apply the gain correction and shift, note the result is in Q3.13 format
         */
        Gain1 = (Gain1 * GainCorrect) << 4;
        Gain2 = (Gain2 * GainCorrect) << 4;
        }
        else
        {
            Gain1 = Gain1 << 16;
            Gain2 = Gain2 << 16;
        }



        /*
         * Set the gain values
         */
        pConfig->Output_Shift = pConfig->Output_Shift;
        LVC_Mixer_SetTarget(&pConfig->Mixer_Instance.MixerStream[0],Gain1>>16);
        LVC_Mixer_VarSlope_SetTimeConstant(&pConfig->Mixer_Instance.MixerStream[0],LVCS_BYPASS_MIXER_TC,pParams->SampleRate,2);
        LVC_Mixer_SetTarget(&pConfig->Mixer_Instance.MixerStream[1],Gain2>>16);
        LVC_Mixer_VarSlope_SetTimeConstant(&pConfig->Mixer_Instance.MixerStream[1],LVCS_BYPASS_MIXER_TC,pParams->SampleRate,2);
    }

    return(LVCS_SUCCESS);

}

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                LVCS_BypassMixer                                        */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Apply Bypass Mix.                                                               */
/*                                                                                  */
/*  This mixes the processed and unprocessed data streams together to correct the   */
/*  overall system gain and allow progressive control of the Concert Sound effect.  */
/*                                                                                  */
/*  When the bypass mixer is enabled the output is the processed signal only and    */
/*  without gain correction.                                                        */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance               Instance Handle                                         */
/*  pProcessed              Pointer to the processed data                           */
/*  pUnprocessed            Pointer to the unprocessed data                         */
/*  pOutData                Pointer to the output data                              */
/*  NumSamples              Number of samples to process                            */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVCS_Success            Always succeeds                                         */
/*                                                                                  */
/* NOTES:                                                                           */
/*                                                                                  */
/************************************************************************************/

LVCS_ReturnStatus_en LVCS_BypassMixer(LVCS_Handle_t         hInstance,
                                      const LVM_INT16       *pProcessed,
                                      const LVM_INT16       *pUnprocessed,
                                      LVM_INT16             *pOutData,
                                      LVM_UINT16            NumSamples)
{

    LVCS_Instance_t     *pInstance      = (LVCS_Instance_t  *)hInstance;
    LVCS_BypassMix_t    *pConfig        = (LVCS_BypassMix_t *)&pInstance->BypassMix;

    /*
     * Check if the bypass mixer is enabled
     */
    if ((pInstance->Params.OperatingMode & LVCS_BYPASSMIXSWITCH) != 0)
    {
        /*
         * Apply the bypass mix
         */
        LVC_MixSoft_2St_D16C31_SAT(&pConfig->Mixer_Instance,
                                        pProcessed,
                                        (LVM_INT16 *) pUnprocessed,
                                        pOutData,
                                        (LVM_INT16)(2*NumSamples));

        /*
         * Apply output gain correction shift
         */
        Shift_Sat_v16xv16 ((LVM_INT16)pConfig->Output_Shift,
                          (LVM_INT16*)pOutData,
                          (LVM_INT16*)pOutData,
                          (LVM_INT16)(2*NumSamples));          /* Left and right*/
    }

    return(LVCS_SUCCESS);
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                LVCS_MixerCallback                                      */
/*                                                                                  */
/************************************************************************************/
LVM_INT32 LVCS_MixerCallback(LVCS_Handle_t      hInstance,
                            void                *pGeneralPurpose,
                            LVM_INT16           CallbackParam)
{
    LVCS_Instance_t     *pInstance = (LVCS_Instance_t  *)hInstance;

   (void)pGeneralPurpose;

    /*
     * Off transition has completed in Headphone mode
     */
    if ((pInstance->OutputDevice == LVCS_HEADPHONE) &&
        (pInstance->bInOperatingModeTransition)     &&
        (pInstance->MSTarget0 == 0x0000)&&  /* this indicates an on->off transition */
        (CallbackParam == 0))
    {
        /* Set operating mode to OFF */
        pInstance->Params.OperatingMode = LVCS_OFF;

        /* Exit transition state */
        pInstance->bInOperatingModeTransition = LVM_FALSE;

        /* Signal to the bundle */
        if((*pInstance->Capabilities.CallBack) != LVM_NULL){
            (*pInstance->Capabilities.CallBack)(pInstance->Capabilities.pBundleInstance,
                                                LVM_NULL,
                                                (ALGORITHM_CS_ID | LVCS_EVENT_ALGOFF));
        }
    }


    if ((pInstance->OutputDevice == LVCS_HEADPHONE)  &&
        (pInstance->MSTarget0 == 1) &&
        (pInstance->bTimerDone == LVM_TRUE)){

        /* Exit transition state */
        pInstance->bInOperatingModeTransition = LVM_FALSE;
    }

    return 1;
}



