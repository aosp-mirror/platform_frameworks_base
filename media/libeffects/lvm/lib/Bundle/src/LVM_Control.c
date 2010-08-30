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
/* Includes                                                                             */
/*                                                                                      */
/****************************************************************************************/

#include "VectorArithmetic.h"
#include "ScalarArithmetic.h"
#include "LVM_Coeffs.h"
#include "LVM_Tables.h"
#include "LVM_Private.h"

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:           LVM_SetControlParameters                                         */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Sets or changes the LifeVibes module parameters.                                    */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance          Instance handle                                                  */
/*  pParams            Pointer to a parameter structure                                 */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS        Succeeded                                                        */
/*  LVM_NULLADDRESS    When hInstance, pParams or any control pointers are NULL         */
/*  LVM_OUTOFRANGE     When any of the control parameters are out of range              */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. This function may be interrupted by the LVM_Process function                     */
/*                                                                                      */
/****************************************************************************************/

LVM_ReturnStatus_en LVM_SetControlParameters(LVM_Handle_t           hInstance,
                                             LVM_ControlParams_t    *pParams)
{
    LVM_Instance_t    *pInstance =(LVM_Instance_t  *)hInstance;


    if ((pParams == LVM_NULL) || (hInstance == LVM_NULL))
    {
        return (LVM_NULLADDRESS);
    }

    pInstance->NewParams = *pParams;

    if(
        /* General parameters */
        ((pParams->OperatingMode != LVM_MODE_OFF) && (pParams->OperatingMode != LVM_MODE_ON))                                         ||
        ((pParams->SampleRate != LVM_FS_8000) && (pParams->SampleRate != LVM_FS_11025) && (pParams->SampleRate != LVM_FS_12000)       &&
        (pParams->SampleRate != LVM_FS_16000) && (pParams->SampleRate != LVM_FS_22050) && (pParams->SampleRate != LVM_FS_24000)       &&
        (pParams->SampleRate != LVM_FS_32000) && (pParams->SampleRate != LVM_FS_44100) && (pParams->SampleRate != LVM_FS_48000))      ||
        ((pParams->SourceFormat != LVM_STEREO) && (pParams->SourceFormat != LVM_MONOINSTEREO) && (pParams->SourceFormat != LVM_MONO)) ||
        (pParams->SpeakerType > LVM_EX_HEADPHONES))
    {
        return (LVM_OUTOFRANGE);
    }

    /*
     * Cinema Sound parameters
     */
    if((pParams->VirtualizerOperatingMode != LVM_MODE_OFF) && (pParams->VirtualizerOperatingMode != LVM_MODE_ON))
    {
        return (LVM_OUTOFRANGE);
    }

    if(pParams->VirtualizerType != LVM_CONCERTSOUND)
    {
        return (LVM_OUTOFRANGE);
    }

    if(pParams->VirtualizerReverbLevel > LVM_VIRTUALIZER_MAX_REVERB_LEVEL)
    {
        return (LVM_OUTOFRANGE);
    }

    if(pParams->CS_EffectLevel < LVM_CS_MIN_EFFECT_LEVEL)
    {
        return (LVM_OUTOFRANGE);
    }

    /*
     * N-Band Equalizer
     */
    if(pParams->EQNB_NBands > pInstance->InstParams.EQNB_NumBands)
    {
        return (LVM_OUTOFRANGE);
    }

    /* Definition pointer */
    if ((pParams->pEQNB_BandDefinition == LVM_NULL) &&
        (pParams->EQNB_NBands != 0))
    {
        return (LVM_NULLADDRESS);
    }

    /*
     * Copy the filter definitions for the Equaliser
     */
    {
        LVM_INT16           i;

        if (pParams->EQNB_NBands != 0)
        {
            for (i=0; i<pParams->EQNB_NBands; i++)
            {
                pInstance->pEQNB_BandDefs[i] = pParams->pEQNB_BandDefinition[i];
            }
            pInstance->NewParams.pEQNB_BandDefinition = pInstance->pEQNB_BandDefs;
        }
    }
    if( /* N-Band Equaliser parameters */
        ((pParams->EQNB_OperatingMode != LVM_EQNB_OFF) && (pParams->EQNB_OperatingMode != LVM_EQNB_ON)) ||
        (pParams->EQNB_NBands > pInstance->InstParams.EQNB_NumBands))
    {
        return (LVM_OUTOFRANGE);
    }
    /* Band parameters*/
    {
        LVM_INT16 i;
        for(i = 0; i < pParams->EQNB_NBands; i++)
        {
            if(((pParams->pEQNB_BandDefinition[i].Frequency < LVM_EQNB_MIN_BAND_FREQ)  ||
                (pParams->pEQNB_BandDefinition[i].Frequency > LVM_EQNB_MAX_BAND_FREQ)) ||
                ((pParams->pEQNB_BandDefinition[i].Gain     < LVM_EQNB_MIN_BAND_GAIN)  ||
                (pParams->pEQNB_BandDefinition[i].Gain      > LVM_EQNB_MAX_BAND_GAIN)) ||
                ((pParams->pEQNB_BandDefinition[i].QFactor  < LVM_EQNB_MIN_QFACTOR)     ||
                (pParams->pEQNB_BandDefinition[i].QFactor   > LVM_EQNB_MAX_QFACTOR)))
            {
                return (LVM_OUTOFRANGE);
            }
        }
    }

    /*
     * Bass Enhancement parameters
     */
    if(((pParams->BE_OperatingMode != LVM_BE_OFF) && (pParams->BE_OperatingMode != LVM_BE_ON))                      ||
        ((pParams->BE_EffectLevel < LVM_BE_MIN_EFFECTLEVEL ) || (pParams->BE_EffectLevel > LVM_BE_MAX_EFFECTLEVEL ))||
        ((pParams->BE_CentreFreq != LVM_BE_CENTRE_55Hz) && (pParams->BE_CentreFreq != LVM_BE_CENTRE_66Hz)           &&
        (pParams->BE_CentreFreq != LVM_BE_CENTRE_78Hz) && (pParams->BE_CentreFreq != LVM_BE_CENTRE_90Hz))           ||
        ((pParams->BE_HPF != LVM_BE_HPF_OFF) && (pParams->BE_HPF != LVM_BE_HPF_ON)))
    {
        return (LVM_OUTOFRANGE);
    }

    /*
     * Volume Control parameters
     */
    if((pParams->VC_EffectLevel < LVM_VC_MIN_EFFECTLEVEL ) || (pParams->VC_EffectLevel > LVM_VC_MAX_EFFECTLEVEL ))
    {
        return (LVM_OUTOFRANGE);
    }
    if((pParams->VC_Balance < LVM_VC_BALANCE_MIN ) || (pParams->VC_Balance > LVM_VC_BALANCE_MAX ))
    {
        return (LVM_OUTOFRANGE);
    }

    /*
     * PSA parameters
     */
    if( (pParams->PSA_PeakDecayRate > LVPSA_SPEED_HIGH) ||
        (pParams->PSA_Enable > LVM_PSA_ON))
    {
        return (LVM_OUTOFRANGE);
    }


    /*
    * Set the flag to indicate there are new parameters to use
    *
    * Protect the copy of the new parameters from interrupts to avoid possible problems
    * with loss control parameters. This problem can occur if this control function is called more
    * than once before a call to the process function. If the process function interrupts
    * the copy to NewParams then one frame may have mixed parameters, some old and some new.
    */
    pInstance->ControlPending = LVM_TRUE;

    return(LVM_SUCCESS);
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:             LVM_GetControlParameters                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Request the LifeVibes module parameters. The current parameter set is returned      */
/*  via the parameter pointer.                                                          */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance            Instance handle                                                */
/*  pParams              Pointer to an empty parameter structure                        */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS          Succeeded                                                      */
/*  LVM_NULLADDRESS      when any of hInstance or pParams is NULL                       */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. This function may be interrupted by the LVM_Process function                     */
/*                                                                                      */
/****************************************************************************************/

LVM_ReturnStatus_en LVM_GetControlParameters(LVM_Handle_t           hInstance,
                                             LVM_ControlParams_t    *pParams)
{
    LVM_Instance_t    *pInstance =(LVM_Instance_t  *)hInstance;


    /*
     * Check pointer
     */
    if ((pParams == LVM_NULL) || (hInstance == LVM_NULL))
    {
        return (LVM_NULLADDRESS);
    }
    *pParams = pInstance->NewParams;

    /*
     * Copy the filter definitions for the Equaliser
     */
    {
        LVM_INT16           i;

        if (pInstance->NewParams.EQNB_NBands != 0)
        for (i=0; i<pInstance->NewParams.EQNB_NBands; i++)
        {
            pInstance->pEQNB_UserDefs[i] = pInstance->pEQNB_BandDefs[i];
        }
        pParams->pEQNB_BandDefinition = pInstance->pEQNB_UserDefs;
    }

    return(LVM_SUCCESS);
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_SetTrebleBoost                                          */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Enable the treble boost when the settings are appropriate, i.e. non-zero gain       */
/*  and the sample rate is high enough for the effect to be heard.                      */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  pInstance               Pointer to the instance structure                           */
/*  pParams                 Pointer to the parameters to use                            */
/*                                                                                      */
/****************************************************************************************/
void LVM_SetTrebleBoost(LVM_Instance_t         *pInstance,
                        LVM_ControlParams_t    *pParams)
{
    extern FO_C16_LShx_Coefs_t  LVM_TrebleBoostCoefs[];
    LVM_INT16               Offset;
    LVM_INT16               EffectLevel = 0;

    /*
     * Load the coefficients
     */
    if ((pParams->TE_OperatingMode == LVM_TE_ON) &&
        (pParams->SampleRate >= TrebleBoostMinRate) &&
        (pParams->OperatingMode == LVM_MODE_ON) &&
        (pParams->TE_EffectLevel > 0))
    {
        if((pParams->TE_EffectLevel == LVM_TE_LOW_MIPS) &&
            ((pParams->SpeakerType == LVM_HEADPHONES)||
            (pParams->SpeakerType == LVM_EX_HEADPHONES)))
        {
            pInstance->TE_Active = LVM_FALSE;
        }
        else
        {
            EffectLevel = pParams->TE_EffectLevel;
            pInstance->TE_Active = LVM_TRUE;
        }

        if(pInstance->TE_Active == LVM_TRUE)
        {
            /*
             * Load the coefficients and enabled the treble boost
             */
            Offset = (LVM_INT16)(EffectLevel - 1 + TrebleBoostSteps * (pParams->SampleRate - TrebleBoostMinRate));
            FO_2I_D16F32Css_LShx_TRC_WRA_01_Init(&pInstance->pTE_State->TrebleBoost_State,
                                            &pInstance->pTE_Taps->TrebleBoost_Taps,
                                            &LVM_TrebleBoostCoefs[Offset]);

            /*
             * Clear the taps
             */
            LoadConst_16((LVM_INT16)0,                                     /* Value */
                         (void *)&pInstance->pTE_Taps->TrebleBoost_Taps,  /* Destination.\
                                                     Cast to void: no dereferencing in function */
                         (LVM_UINT16)(sizeof(pInstance->pTE_Taps->TrebleBoost_Taps)/sizeof(LVM_INT16))); /* Number of words */
        }
    }
    else
    {
        /*
         * Disable the treble boost
         */
        pInstance->TE_Active = LVM_FALSE;
    }

    return;
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVM_SetVolume                                               */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Converts the input volume demand from dBs to linear.                            */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInstance           Pointer to the instance                                     */
/*  pParams             Initialisation parameters                                   */
/*                                                                                  */
/************************************************************************************/
void    LVM_SetVolume(LVM_Instance_t         *pInstance,
                      LVM_ControlParams_t    *pParams)
{

    LVM_UINT16      dBShifts;                                   /* 6dB shifts */
    LVM_UINT16      dBOffset;                                   /* Table offset */
    LVM_INT16       Volume = 0;                                 /* Required volume in dBs */

    /*
     * Limit the gain to the maximum allowed
     */
     if  (pParams->VC_EffectLevel > 0)
     {
         Volume = 0;
     }
     else
     {
         Volume = pParams->VC_EffectLevel;
     }

     /* Compensate this volume in PSA plot */
     if(Volume > -60)  /* Limit volume loss to PSA Limits*/
         pInstance->PSA_GainOffset=(LVM_INT16)(-Volume);/* Loss is compensated by Gain*/
     else
         pInstance->PSA_GainOffset=(LVM_INT16)60;/* Loss is compensated by Gain*/

    pInstance->VC_AVLFixedVolume = 0;

    /*
     * Set volume control and AVL volumes according to headroom and volume user setting
     */
    if(pParams->OperatingMode == LVM_MODE_ON)
    {
        /* Default Situation with no AVL and no RS */
        if(pParams->EQNB_OperatingMode == LVM_EQNB_ON)
        {
            if(Volume > -pInstance->Headroom)
                Volume = (LVM_INT16)-pInstance->Headroom;
        }
    }

    /*
     * Activate volume control if necessary
     */
    pInstance->VC_Active   = LVM_TRUE;
    if (Volume != 0)
    {
        pInstance->VC_VolumedB = Volume;
    }
    else
    {
        pInstance->VC_VolumedB = 0;
    }

    /*
     * Calculate the required gain and shifts
     */
    dBOffset = (LVM_UINT16)((-Volume) % 6);             /* Get the dBs 0-5 */
    dBShifts = (LVM_UINT16)(Volume / -6);               /* Get the 6dB shifts */


    /*
     * Set the parameters
     */
    if(dBShifts == 0)
    {
        LVC_Mixer_SetTarget(&pInstance->VC_Volume.MixerStream[0],
                                (LVM_INT32)LVM_VolumeTable[dBOffset]);
    }
    else
    {
        LVC_Mixer_SetTarget(&pInstance->VC_Volume.MixerStream[0],
                                (((LVM_INT32)LVM_VolumeTable[dBOffset])>>dBShifts));
    }
    pInstance->VC_Volume.MixerStream[0].CallbackSet = 1;
    if(pInstance->NoSmoothVolume == LVM_TRUE)
    {
        LVC_Mixer_SetTimeConstant(&pInstance->VC_Volume.MixerStream[0],0,pInstance->Params.SampleRate,2);
    }
    else
    {
        LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->VC_Volume.MixerStream[0],LVM_VC_MIXER_TIME,pInstance->Params.SampleRate,2);
    }
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVM_SetHeadroom                                             */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Find suitable headroom based on EQ settings.                                    */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInstance           Pointer to the instance                                     */
/*  pParams             Initialisation parameters                                   */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  void                Nothing                                                     */
/*                                                                                  */
/* NOTES:                                                                           */
/*                                                                                  */
/************************************************************************************/
void    LVM_SetHeadroom(LVM_Instance_t         *pInstance,
                        LVM_ControlParams_t    *pParams)
{
    LVM_INT16   ii, jj;
    LVM_INT16   Headroom = 0;
    LVM_INT16   MaxGain = 0;


    if ((pParams->EQNB_OperatingMode == LVEQNB_ON) && (pInstance->HeadroomParams.Headroom_OperatingMode == LVM_HEADROOM_ON))
    {
        /* Find typical headroom value */
        for(jj = 0; jj < pInstance->HeadroomParams.NHeadroomBands; jj++)
        {
            MaxGain = 0;
            for( ii = 0; ii < pParams->EQNB_NBands; ii++)
            {
                if((pParams->pEQNB_BandDefinition[ii].Frequency >= pInstance->HeadroomParams.pHeadroomDefinition[jj].Limit_Low) &&
                   (pParams->pEQNB_BandDefinition[ii].Frequency <= pInstance->HeadroomParams.pHeadroomDefinition[jj].Limit_High))
                {
                    if(pParams->pEQNB_BandDefinition[ii].Gain > MaxGain)
                    {
                        MaxGain = pParams->pEQNB_BandDefinition[ii].Gain;
                    }
                }
            }

            if((MaxGain - pInstance->HeadroomParams.pHeadroomDefinition[jj].Headroom_Offset) > Headroom){
                Headroom = (LVM_INT16)(MaxGain - pInstance->HeadroomParams.pHeadroomDefinition[jj].Headroom_Offset);
            }
        }

        /* Saturate */
        if(Headroom < 0)
            Headroom = 0;
    }
    pInstance->Headroom = (LVM_UINT16)Headroom ;

}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_ApplyNewSettings                                        */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Applies changes to parametres. This function makes no assumptions about what        */
/*  each module needs for initialisation and hence passes all parameters to all the     */
/*  the modules in turn.                                                                */
/*                                                                                      */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_Success             Succeeded                                                   */
/*                                                                                      */
/****************************************************************************************/

LVM_ReturnStatus_en LVM_ApplyNewSettings(LVM_Handle_t   hInstance)
{
    LVM_Instance_t         *pInstance =(LVM_Instance_t *)hInstance;
    LVM_ControlParams_t    LocalParams;
    LVM_INT16              Count = 5;


    /*
     * Copy the new parameters but make sure they didn't change while copying
     */
    do
    {
        pInstance->ControlPending = LVM_FALSE;
        LocalParams = pInstance->NewParams;
        pInstance->HeadroomParams = pInstance->NewHeadroomParams;
        Count--;
    } while ((pInstance->ControlPending != LVM_FALSE) &&
             (Count > 0));

    /* Clear all internal data if format change*/
    if(LocalParams.SourceFormat != pInstance->Params.SourceFormat)
    {
        LVM_ClearAudioBuffers(pInstance);
        pInstance->ControlPending = LVM_FALSE;
    }

    /*
     * Update the treble boost if required
     */
    if ((pInstance->Params.SampleRate != LocalParams.SampleRate) ||
        (pInstance->Params.TE_EffectLevel != LocalParams.TE_EffectLevel) ||
        (pInstance->Params.TE_OperatingMode != LocalParams.TE_OperatingMode) ||
        (pInstance->Params.OperatingMode != LocalParams.OperatingMode) ||
        (pInstance->Params.SpeakerType != LocalParams.SpeakerType))
    {
        LVM_SetTrebleBoost(pInstance,
                           &LocalParams);
    }

    /*
     * Update the headroom if required
     */
        LVM_SetHeadroom(pInstance,                      /* Instance pointer */
                        &LocalParams);                  /* New parameters */

    /*
     * Update the volume if required
     */
    {
        LVM_SetVolume(pInstance,                      /* Instance pointer */
                      &LocalParams);                  /* New parameters */
    }
    /* Apply balance changes*/
    if(pInstance->Params.VC_Balance != LocalParams.VC_Balance)
    {
        /* Configure Mixer module for gradual changes to volume*/
        if(LocalParams.VC_Balance < 0)
        {
            LVM_INT32 Target;
            /* Drop in right channel volume*/
            Target = LVM_MAXINT_16;
            LVC_Mixer_SetTarget(&pInstance->VC_BalanceMix.MixerStream[0],Target);
            LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->VC_BalanceMix.MixerStream[0],LVM_VC_MIXER_TIME,LocalParams.SampleRate,1);

            Target = dB_to_Lin32((LVM_INT16)(LocalParams.VC_Balance<<4));
            LVC_Mixer_SetTarget(&pInstance->VC_BalanceMix.MixerStream[1],Target);
            LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->VC_BalanceMix.MixerStream[1],LVM_VC_MIXER_TIME,LocalParams.SampleRate,1);
        }
        else if(LocalParams.VC_Balance >0)
        {
            LVM_INT32 Target;
            /* Drop in left channel volume*/
            Target = dB_to_Lin32((LVM_INT16)((-LocalParams.VC_Balance)<<4));
            LVC_Mixer_SetTarget(&pInstance->VC_BalanceMix.MixerStream[0],Target);
            LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->VC_BalanceMix.MixerStream[0],LVM_VC_MIXER_TIME,LocalParams.SampleRate,1);

            Target = LVM_MAXINT_16;
            LVC_Mixer_SetTarget(&pInstance->VC_BalanceMix.MixerStream[1],Target);
            LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->VC_BalanceMix.MixerStream[1],LVM_VC_MIXER_TIME,LocalParams.SampleRate,1);
        }
        else
        {
            LVM_INT32 Target;
            /* No drop*/
            Target = LVM_MAXINT_16;
            LVC_Mixer_SetTarget(&pInstance->VC_BalanceMix.MixerStream[0],Target);
            LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->VC_BalanceMix.MixerStream[0],LVM_VC_MIXER_TIME,LocalParams.SampleRate,1);

            LVC_Mixer_SetTarget(&pInstance->VC_BalanceMix.MixerStream[1],Target);
            LVC_Mixer_VarSlope_SetTimeConstant(&pInstance->VC_BalanceMix.MixerStream[1],LVM_VC_MIXER_TIME,LocalParams.SampleRate,1);
        }
    }
    /*
     * Update the bass enhancement
     */
    {
        LVDBE_ReturnStatus_en       DBE_Status;
        LVDBE_Params_t              DBE_Params;
        LVDBE_Handle_t              *hDBEInstance = pInstance->hDBEInstance;


        /*
         * Set the new parameters
         */
        if(LocalParams.OperatingMode == LVM_MODE_OFF)
        {
            DBE_Params.OperatingMode = LVDBE_OFF;
        }
        else
        {
            DBE_Params.OperatingMode    = (LVDBE_Mode_en)LocalParams.BE_OperatingMode;
        }
        DBE_Params.SampleRate       = (LVDBE_Fs_en)LocalParams.SampleRate;
        DBE_Params.EffectLevel      = LocalParams.BE_EffectLevel;
        DBE_Params.CentreFrequency  = (LVDBE_CentreFreq_en)LocalParams.BE_CentreFreq;
        DBE_Params.HPFSelect        = (LVDBE_FilterSelect_en)LocalParams.BE_HPF;
        DBE_Params.HeadroomdB       = 0;
        DBE_Params.VolumeControl    = LVDBE_VOLUME_OFF;
        DBE_Params.VolumedB         = 0;

        /*
         * Make the changes
         */
        DBE_Status = LVDBE_Control(hDBEInstance,
                                   &DBE_Params);


        /*
         * Quit if the changes were not accepted
         */
        if (DBE_Status != LVDBE_SUCCESS)
        {
            return((LVM_ReturnStatus_en)DBE_Status);
        }


        /*
         * Set the control flag
         */
        pInstance->DBE_Active = LVM_TRUE;
    }

    /*
     * Update the N-Band Equaliser
     */
    {
        LVEQNB_ReturnStatus_en      EQNB_Status;
        LVEQNB_Params_t             EQNB_Params;
        LVEQNB_Handle_t             *hEQNBInstance = pInstance->hEQNBInstance;


        /*
         * Set the new parameters
         */

        if(LocalParams.OperatingMode == LVM_MODE_OFF)
        {
            EQNB_Params.OperatingMode    = LVEQNB_BYPASS;
        }
        else
        {
            EQNB_Params.OperatingMode    = (LVEQNB_Mode_en)LocalParams.EQNB_OperatingMode;
        }

        EQNB_Params.SampleRate       = (LVEQNB_Fs_en)LocalParams.SampleRate;
        EQNB_Params.NBands           = LocalParams.EQNB_NBands;
        EQNB_Params.pBandDefinition  = (LVEQNB_BandDef_t *)LocalParams.pEQNB_BandDefinition;
        if (LocalParams.SourceFormat == LVM_STEREO)    /* Mono format not supported */
        {
            EQNB_Params.SourceFormat = LVEQNB_STEREO;
        }
        else
        {
            EQNB_Params.SourceFormat = LVEQNB_MONOINSTEREO;     /* Force to Mono-in-Stereo mode */
        }


        /*
         * Set the control flag
         */
        if ((LocalParams.OperatingMode == LVM_MODE_ON) &&
            (LocalParams.EQNB_OperatingMode == LVM_EQNB_ON))
        {
            pInstance->EQNB_Active = LVM_TRUE;
        }
        else
        {
            EQNB_Params.OperatingMode = LVEQNB_BYPASS;
        }

        /*
         * Make the changes
         */
        EQNB_Status = LVEQNB_Control(hEQNBInstance,
                                     &EQNB_Params);


        /*
         * Quit if the changes were not accepted
         */
        if (EQNB_Status != LVEQNB_SUCCESS)
        {
            return((LVM_ReturnStatus_en)EQNB_Status);
        }

    }


    /*
     * Update concert sound
     */
    {
        LVCS_ReturnStatus_en        CS_Status;
        LVCS_Params_t               CS_Params;
        LVCS_Handle_t               *hCSInstance = pInstance->hCSInstance;
        LVM_Mode_en                 CompressorMode=LVM_MODE_ON;

        /*
         * Set the new parameters
         */
        if(LocalParams.VirtualizerOperatingMode == LVM_MODE_ON)
        {
            CS_Params.OperatingMode    = LVCS_ON;
        }
        else
        {
            CS_Params.OperatingMode    = LVCS_OFF;
        }

        if((LocalParams.TE_OperatingMode == LVM_TE_ON) && (LocalParams.TE_EffectLevel == LVM_TE_LOW_MIPS))
        {
            CS_Params.SpeakerType  = LVCS_EX_HEADPHONES;
        }
        else
        {
            CS_Params.SpeakerType  = LVCS_HEADPHONES;
        }

        if (LocalParams.SourceFormat == LVM_STEREO)    /* Mono format not supported */
        {
            CS_Params.SourceFormat = LVCS_STEREO;
        }
        else
        {
            CS_Params.SourceFormat = LVCS_MONOINSTEREO;          /* Force to Mono-in-Stereo mode */
        }
        CS_Params.SampleRate  = LocalParams.SampleRate;
        CS_Params.ReverbLevel = LocalParams.VirtualizerReverbLevel;
        CS_Params.EffectLevel = LocalParams.CS_EffectLevel;


        /*
         * Set the control flag
         */
        if ((LocalParams.OperatingMode == LVM_MODE_ON) &&
            (LocalParams.VirtualizerOperatingMode != LVCS_OFF))
        {
            pInstance->CS_Active = LVM_TRUE;
        }
        else
        {
            CS_Params.OperatingMode = LVCS_OFF;
        }

        CS_Params.CompressorMode=CompressorMode;

        /*
         * Make the changes
         */
        CS_Status = LVCS_Control(hCSInstance,
                                 &CS_Params);


        /*
         * Quit if the changes were not accepted
         */
        if (CS_Status != LVCS_SUCCESS)
        {
            return((LVM_ReturnStatus_en)CS_Status);
        }

    }

    /*
     * Update the Power Spectrum Analyser
     */
    {
        LVPSA_RETURN                PSA_Status;
        LVPSA_ControlParams_t       PSA_Params;
        pLVPSA_Handle_t             *hPSAInstance = pInstance->hPSAInstance;


        /*
         * Set the new parameters
         */
        PSA_Params.Fs = LocalParams.SampleRate;
        PSA_Params.LevelDetectionSpeed = (LVPSA_LevelDetectSpeed_en)LocalParams.PSA_PeakDecayRate;

        /*
         * Make the changes
         */
        if(pInstance->InstParams.PSA_Included==LVM_PSA_ON)
        {
            PSA_Status = LVPSA_Control(hPSAInstance,
                &PSA_Params);

            if (PSA_Status != LVPSA_OK)
            {
                return((LVM_ReturnStatus_en)PSA_Status);
            }

            /*
             * Apply new settings
             */
            PSA_Status = LVPSA_ApplyNewSettings ((LVPSA_InstancePr_t*)hPSAInstance);
            if(PSA_Status != LVPSA_OK)
            {
                return((LVM_ReturnStatus_en)PSA_Status);
            }
        }
    }

    /*
     * Update the parameters and clear the flag
     */
    pInstance->NoSmoothVolume = LVM_FALSE;
    pInstance->Params =  LocalParams;


    return(LVM_SUCCESS);
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_SetHeadroomParams                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used to set the automatiuc headroom management parameters.         */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance Handle                                             */
/*  pHeadroomParams         Pointer to headroom parameter structure                     */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_Success             Succeeded                                                   */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVM_Process function                    */
/*                                                                                      */
/****************************************************************************************/

LVM_ReturnStatus_en LVM_SetHeadroomParams(LVM_Handle_t              hInstance,
                                          LVM_HeadroomParams_t      *pHeadroomParams)
{
    LVM_Instance_t      *pInstance =(LVM_Instance_t  *)hInstance;
    LVM_UINT16          ii, NBands;

    /* Check for NULL pointers */
    if ((hInstance == LVM_NULL) || (pHeadroomParams == LVM_NULL))
    {
        return (LVM_NULLADDRESS);
    }
    if ((pHeadroomParams->NHeadroomBands != 0) && (pHeadroomParams->pHeadroomDefinition == LVM_NULL))
    {
        return (LVM_NULLADDRESS);
    }

    /* Consider only the LVM_HEADROOM_MAX_NBANDS first bands*/
    if (pHeadroomParams->NHeadroomBands > LVM_HEADROOM_MAX_NBANDS)
    {
        NBands = LVM_HEADROOM_MAX_NBANDS;
    }
    else
    {
        NBands = pHeadroomParams->NHeadroomBands;
    }
    pInstance->NewHeadroomParams.NHeadroomBands = NBands;

    /* Copy settings in memory */
    for(ii = 0; ii < NBands; ii++)
    {
        pInstance->pHeadroom_BandDefs[ii] = pHeadroomParams->pHeadroomDefinition[ii];
    }

    pInstance->NewHeadroomParams.pHeadroomDefinition = pInstance->pHeadroom_BandDefs;
    pInstance->NewHeadroomParams.Headroom_OperatingMode = pHeadroomParams->Headroom_OperatingMode;
    pInstance->ControlPending = LVM_TRUE;

    return(LVM_SUCCESS);
}

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_GetHeadroomParams                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function is used to get the automatic headroom management parameters.          */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance Handle                                             */
/*  pHeadroomParams         Pointer to headroom parameter structure (output)            */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Succeeded                                                   */
/*  LVM_NULLADDRESS         When hInstance or pHeadroomParams are NULL                  */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVM_Process function                    */
/*                                                                                      */
/****************************************************************************************/

LVM_ReturnStatus_en LVM_GetHeadroomParams(LVM_Handle_t          hInstance,
                                          LVM_HeadroomParams_t  *pHeadroomParams)
{
    LVM_Instance_t      *pInstance =(LVM_Instance_t  *)hInstance;
    LVM_UINT16          ii;

    /* Check for NULL pointers */
    if ((hInstance == LVM_NULL) || (pHeadroomParams == LVM_NULL))
    {
        return (LVM_NULLADDRESS);
    }

    pHeadroomParams->NHeadroomBands = pInstance->NewHeadroomParams.NHeadroomBands;


    /* Copy settings in memory */
    for(ii = 0; ii < pInstance->NewHeadroomParams.NHeadroomBands; ii++)
    {
        pInstance->pHeadroom_UserDefs[ii] = pInstance->pHeadroom_BandDefs[ii];
    }


    pHeadroomParams->pHeadroomDefinition = pInstance->pHeadroom_UserDefs;
    pHeadroomParams->Headroom_OperatingMode = pInstance->NewHeadroomParams.Headroom_OperatingMode;
    return(LVM_SUCCESS);
}

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_AlgoCallBack                                            */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This is the callback function of the algorithm.                                     */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  pBundleHandle           Pointer to the Instance Handle                              */
/*  pData                   Pointer to the data                                         */
/*  callbackId              ID of the callback                                          */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVM_Process function                    */
/*                                                                                      */
/****************************************************************************************/
LVM_INT32 LVM_AlgoCallBack( void          *pBundleHandle,
                            void          *pData,
                            LVM_INT16     callbackId)
{
    LVM_Instance_t      *pInstance =(LVM_Instance_t  *)pBundleHandle;

    (void) pData;

    switch(callbackId & 0xFF00){
        case ALGORITHM_CS_ID:
            switch(callbackId & 0x00FF)
            {
                case LVCS_EVENT_ALGOFF:
                    pInstance->CS_Active = LVM_FALSE;
                    break;
                default:
                    break;
            }
            break;
        case ALGORITHM_EQNB_ID:
            switch(callbackId & 0x00FF)
            {
                case LVEQNB_EVENT_ALGOFF:
                    pInstance->EQNB_Active = LVM_FALSE;
                    break;
                default:
                    break;
            }
            break;
        default:
            break;
    }

    return 0;
}

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_VCCallBack                                              */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This is the callback function of the Volume control.                                */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  pBundleHandle           Pointer to the Instance Handle                              */
/*  pGeneralPurpose         Pointer to the data                                         */
/*  CallBackParam           ID of the callback                                          */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVM_Process function                    */
/*                                                                                      */
/****************************************************************************************/
LVM_INT32    LVM_VCCallBack(void*   pBundleHandle,
                            void*   pGeneralPurpose,
                            short   CallBackParam)
{
    LVM_Instance_t *pInstance =(LVM_Instance_t  *)pBundleHandle;
    LVM_INT32    Target;

    (void) pGeneralPurpose;
    (void) CallBackParam;

    /* When volume mixer has reached 0 dB target then stop it to avoid
       unnecessary processing. */
    Target = LVC_Mixer_GetTarget(&pInstance->VC_Volume.MixerStream[0]);

    if(Target == 0x7FFF)
    {
        pInstance->VC_Active = LVM_FALSE;
    }
    return 1;
}
