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
#include "LVCS_Tables.h"

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                 LVCS_GetParameters                                     */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Request the Concert Sound parameters. The current parameter set is returned     */
/*  via the parameter pointer.                                                      */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance                Instance handle                                        */
/*  pParams                  Pointer to an empty parameter structure                */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVCS_Success             Always succeeds                                        */
/*                                                                                  */
/* NOTES:                                                                           */
/*  1.  This function may be interrupted by the LVCS_Process function               */
/*                                                                                  */
/************************************************************************************/

LVCS_ReturnStatus_en LVCS_GetParameters(LVCS_Handle_t   hInstance,
                                        LVCS_Params_t   *pParams)
{

    LVCS_Instance_t     *pInstance =(LVCS_Instance_t  *)hInstance;

    *pParams = pInstance->Params;

    return(LVCS_SUCCESS);
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                LVCS_Control                                            */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Sets or changes the Concert Sound parameters.                                   */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance               Instance handle                                         */
/*  pParams                 Pointer to a parameter structure                        */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVCS_Success            Succeeded                                               */
/*                                                                                  */
/* NOTES:                                                                           */
/*  1.  This function must not be interrupted by the LVCS_Process function          */
/*                                                                                  */
/************************************************************************************/

LVCS_ReturnStatus_en LVCS_Control(LVCS_Handle_t      hInstance,
                                  LVCS_Params_t      *pParams)
{
    LVM_INT16                   Offset;
    LVCS_Instance_t             *pInstance =(LVCS_Instance_t  *)hInstance;
    LVCS_ReturnStatus_en        err;
    LVCS_Modes_en               OperatingModeSave = pInstance->Params.OperatingMode;

    if (pParams->SampleRate != pInstance->Params.SampleRate)
    {
        pInstance->TimerParams.SamplingRate = LVCS_SampleRateTable[pParams->SampleRate];
    }

    /*
     * If the reverb level has changed
     */
    if(pInstance->Params.ReverbLevel != pParams->ReverbLevel)
    {
        err=LVCS_ReverbGeneratorInit(hInstance,pParams);
    }

    /*
     * If the sample rate or speaker has changed then perform a full re-initialisation
     */
    if ((pInstance->Params.SampleRate != pParams->SampleRate) ||
       (pInstance->Params.SpeakerType != pParams->SpeakerType))
    {
        const LVCS_VolCorrect_t *pLVCS_VolCorrectTable;

        /*
         * Output device
         */
        pInstance->OutputDevice = LVCS_HEADPHONE;

        /*
         * Get the volume correction parameters
         */
        /* Use internal coefficient table */
        pLVCS_VolCorrectTable = (LVCS_VolCorrect_t*)&LVCS_VolCorrectTable[0];
        Offset = (LVM_INT16)(pParams->SpeakerType + pParams->SourceFormat*(1+LVCS_EX_HEADPHONES));

        pInstance->VolCorrect = pLVCS_VolCorrectTable[Offset];

        pInstance->CompressGain = pInstance->VolCorrect.CompMin;

        LVC_Mixer_Init(&pInstance->BypassMix.Mixer_Instance.MixerStream[0],0,0);


        {
            LVM_UINT32          Gain;
            const Gain_t        *pOutputGainTable = (Gain_t*)&LVCS_OutputGainTable[0];
            Gain = (LVM_UINT32)(pOutputGainTable[Offset].Loss * LVM_MAXINT_16);
            Gain = (LVM_UINT32)pOutputGainTable[Offset].UnprocLoss * (Gain >> 15);
            Gain=Gain>>15;
            /*
             * Apply the gain correction and shift, note the result is in Q3.13 format
             */
            Gain = (Gain * pInstance->VolCorrect.GainMin) >>12;

            LVC_Mixer_Init(&pInstance->BypassMix.Mixer_Instance.MixerStream[1],0,Gain);
            LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->BypassMix.Mixer_Instance.MixerStream[0],
                    LVCS_BYPASS_MIXER_TC,pParams->SampleRate,2);
            LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->BypassMix.Mixer_Instance.MixerStream[1],
                    LVCS_BYPASS_MIXER_TC,pParams->SampleRate,2);

        }


        err=LVCS_SEnhancerInit(hInstance,
                           pParams);

        err=LVCS_ReverbGeneratorInit(hInstance,
                                 pParams);

        err=LVCS_EqualiserInit(hInstance,
                           pParams);

        err=LVCS_BypassMixInit(hInstance,
                           pParams);

    }


    /*
     * Check if the effect level or source format has changed
     */
    else if ((pInstance->Params.EffectLevel != pParams->EffectLevel) ||
            (pInstance->Params.SourceFormat != pParams->SourceFormat))
    {
        const LVCS_VolCorrect_t *pLVCS_VolCorrectTable;

        /*
         * Get the volume correction parameters
         */
        /* Use internal coefficient table */
        pLVCS_VolCorrectTable = (LVCS_VolCorrect_t*)&LVCS_VolCorrectTable[0];
        Offset = (LVM_INT16)(pParams->SpeakerType + pParams->SourceFormat*(1+LVCS_EX_HEADPHONES));

        pInstance->VolCorrect = pLVCS_VolCorrectTable[Offset];

        /* Update the effect level and alpha-mixer gains */
        err=LVCS_BypassMixInit(hInstance,
                           pParams);

        if(err != LVCS_SUCCESS)
        {
            return err;
        }
    }
    else
    {
        pInstance->Params = *pParams;
    }

    /*
     * Update the instance parameters
     */
    pInstance->Params = *pParams;

    /* Stay on the current operating mode until the transition is done */
    if((pParams->OperatingMode != OperatingModeSave) ||
       (pInstance->bInOperatingModeTransition == LVM_TRUE)){

        /* Set the reverb delay timeout */
        if(pInstance->bInOperatingModeTransition != LVM_TRUE){
            pInstance->bTimerDone = LVM_FALSE;
            pInstance->TimerParams.TimeInMs =
            (LVM_INT16)(((pInstance->Reverberation.DelaySize << 2)
            /pInstance->TimerParams.SamplingRate) + 1);
            LVM_Timer_Init ( &pInstance->TimerInstance,
                             &pInstance->TimerParams);
        }

        /* Update the effect level and alpha-mixer gains */
        err=LVCS_BypassMixInit(hInstance,
                           pParams);

        /* Change transition bypass mixer settings if needed depending on transition type */
        if(pParams->OperatingMode != LVCS_OFF){
            pInstance->MSTarget0=LVM_MAXINT_16;
            pInstance->MSTarget1=0;
        }
        else
        {
            pInstance->Params.OperatingMode = OperatingModeSave;
            pInstance->MSTarget1=LVM_MAXINT_16;
            pInstance->MSTarget0=0;
        }


        /* Set transition flag */
        pInstance->bInOperatingModeTransition = LVM_TRUE;
    }

    return(LVCS_SUCCESS);
}

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVCS_TimerCallBack                                          */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  CallBack function of the Timer.                                                     */
/*                                                                                      */
/****************************************************************************************/
void LVCS_TimerCallBack (void* hInstance, void* pCallBackParams, LVM_INT32 CallbackParam)
{
    LVCS_Instance_t     *pInstance  = (LVCS_Instance_t  *)hInstance;

    /* Avoid warnings because pCallBackParams and CallbackParam are not used*/
    if((pCallBackParams != LVM_NULL) || (CallbackParam != 0)){
        pCallBackParams = hInstance;
        CallbackParam = 0;
        return;
    }

    pInstance->bTimerDone = LVM_TRUE;


    return;
}

