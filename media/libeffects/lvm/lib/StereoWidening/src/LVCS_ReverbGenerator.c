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
#include "LVCS_ReverbGenerator.h"
#include "LVC_Mixer.h"
#include "VectorArithmetic.h"
#include "BIQUAD.h"
#include "LVCS_Tables.h"

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                LVCS_ReverbGeneratorInit                                */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Initialises the reverb module. The delay buffer size is configured for the      */
/*  sample rate and the speaker type.                                               */
/*                                                                                  */
/*  The routine may also be called for re-initialisation, i.e. when one of the      */
/*  control parameters has changed. In this case the delay and filters are only     */
/*  re-initialised if one of the following two conditions is met:                   */
/*      -   the sample rate has changed                                             */
/*      -   the speaker type changes to/from the mobile speaker                     */
/*                                                                                  */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance               Instance Handle                                         */
/*  pParams                 Pointer to the inialisation parameters                  */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVCS_Success            Always succeeds                                         */
/*                                                                                  */
/* NOTES:                                                                           */
/*  1.  In the delay settings 'Samples' is the number of samples to the end of the  */
/*      buffer.                                                                     */
/*  2.  The numerator coefficients of the filter are negated to cause an inversion. */
/*                                                                                  */
/************************************************************************************/

LVCS_ReturnStatus_en LVCS_ReverbGeneratorInit(LVCS_Handle_t     hInstance,
                                              LVCS_Params_t     *pParams)
{

    LVM_UINT16              Delay;
    LVM_UINT16              Offset;
    LVCS_Instance_t         *pInstance = (LVCS_Instance_t  *)hInstance;
    LVCS_ReverbGenerator_t  *pConfig   = (LVCS_ReverbGenerator_t *)&pInstance->Reverberation;
    LVCS_Data_t             *pData     = (LVCS_Data_t *)pInstance->MemoryTable.Region[LVCS_MEMREGION_PERSISTENT_FAST_DATA].pBaseAddress;
    LVCS_Coefficient_t      *pCoefficients = (LVCS_Coefficient_t *)pInstance->MemoryTable.Region[LVCS_MEMREGION_PERSISTENT_FAST_COEF].pBaseAddress;
    BQ_C16_Coefs_t          Coeffs;
    const BiquadA012B12CoefsSP_t  *pReverbCoefTable;

    /*
     * Initialise the delay and filters if:
     *  - the sample rate has changed
     *  - the speaker type has changed to or from the mobile speaker
     */
    if(pInstance->Params.SampleRate != pParams->SampleRate )      /* Sample rate change test */

    {
        /*
         * Setup the delay
         */
        Delay = (LVM_UINT16)LVCS_StereoDelayCS[(LVM_UINT16)pParams->SampleRate];


        pConfig->DelaySize      = (LVM_INT16)(2 * Delay);
        pConfig->DelayOffset    = 0;
        LoadConst_16(0,                                                                 /* Value */
                     (LVM_INT16 *)&pConfig->StereoSamples[0],                           /* Destination */
                     (LVM_UINT16)(sizeof(pConfig->StereoSamples)/sizeof(LVM_INT16)));   /* Number of words */

        /*
         * Setup the filters
         */
        Offset = (LVM_UINT16)pParams->SampleRate;
        pReverbCoefTable = (BiquadA012B12CoefsSP_t*)&LVCS_ReverbCoefTable[0];

        /* Convert incoming coefficients to the required format/ordering */
        Coeffs.A0 = (LVM_INT16)pReverbCoefTable[Offset].A0;
        Coeffs.A1 = (LVM_INT16)pReverbCoefTable[Offset].A1;
        Coeffs.A2 = (LVM_INT16)pReverbCoefTable[Offset].A2;
        Coeffs.B1 = (LVM_INT16)-pReverbCoefTable[Offset].B1;
        Coeffs.B2 = (LVM_INT16)-pReverbCoefTable[Offset].B2;

        LoadConst_16(0,                                                                 /* Value */
                     (void *)&pData->ReverbBiquadTaps,                             /* Destination Cast to void: no dereferencing in function*/
                     (LVM_UINT16)(sizeof(pData->ReverbBiquadTaps)/sizeof(LVM_INT16)));  /* Number of words */

        BQ_2I_D16F16Css_TRC_WRA_01_Init(&pCoefficients->ReverbBiquadInstance,
                                        &pData->ReverbBiquadTaps,
                                        &Coeffs);

        /* Callbacks */
        switch(pReverbCoefTable[Offset].Scale)
        {
            case 14:
                pConfig->pBiquadCallBack  = BQ_2I_D16F16C14_TRC_WRA_01;
                break;
            case 15:
                pConfig->pBiquadCallBack  = BQ_2I_D16F16C15_TRC_WRA_01;
                break;
        }


        /*
         * Setup the mixer
         */
        pConfig->ProcGain = (LVM_UINT16)(HEADPHONEGAINPROC);
        pConfig->UnprocGain  = (LVM_UINT16)(HEADPHONEGAINUNPROC);
    }

    if(pInstance->Params.ReverbLevel != pParams->ReverbLevel)
    {
        LVM_INT32   ReverbPercentage=83886;                     // 1 Percent Reverb i.e 1/100 in Q 23 format
        ReverbPercentage*=pParams->ReverbLevel;                 // Actual Reverb Level in Q 23 format
        pConfig->ReverbLevel=(LVM_INT16)(ReverbPercentage>>8);  // Reverb Level in Q 15 format
    }

    return(LVCS_SUCCESS);
}

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                LVCS_Reverb                                             */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Create reverb using the block of input samples based on the following block     */
/*  diagram:                                                                        */
/*                           ________              ________                         */
/*                          |        |            |        |                        */
/*     _____     _______    |        |----------->|        |    ______     ___      */
/*    |     |   |       |   | Stereo |            | L & R  |   |      |   |   |     */
/* -->| LPF |-->| Delay |-->|   to   |    ____    |   to   |-->| Gain |-->| + |-->  */
/*  | |_____|   |_______|   | L & R  |   |    |   | Stereo |   |______|   |___|     */
/*  |                       |        |-->| -1 |-->|        |                |       */
/*  |                       |________|   |____|   |________|                |       */
/*  |                                                                       |       */
/*  |-----------------------------------------------------------------------|       */
/*                                                                                  */
/*  The input buffer is broken in to sub-blocks of the size of the delay or less.   */
/*  This allows the delay buffer to be treated as a circular buffer but processed   */
/*  as a linear buffer.                                                             */
/*                                                                                  */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance               Instance Handle                                         */
/*  pInData                 Pointer to the input buffer                             */
/*  pOutData                Pointer to the output buffer                            */
/*  NumSamples              Number of samples to process                            */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVCS_Success            Always succeeds                                         */
/*                                                                                  */
/* NOTES:                                                                           */
/*  1.  Process in blocks of samples the size of the delay where possible, if not   */
/*      the number of samples left over                                             */
/*  2.  The Gain is combined with the LPF and incorporated in to the coefficients   */
/*                                                                                  */
/************************************************************************************/

LVCS_ReturnStatus_en LVCS_ReverbGenerator(LVCS_Handle_t         hInstance,
                                          const LVM_INT16       *pInData,
                                          LVM_INT16             *pOutData,
                                          LVM_UINT16            NumSamples)
{

    LVCS_Instance_t         *pInstance = (LVCS_Instance_t  *)hInstance;
    LVCS_ReverbGenerator_t  *pConfig   = (LVCS_ReverbGenerator_t *)&pInstance->Reverberation;
    LVCS_Coefficient_t      *pCoefficients = (LVCS_Coefficient_t *)pInstance->MemoryTable.Region[LVCS_MEMREGION_PERSISTENT_FAST_COEF].pBaseAddress;
    LVM_INT16               *pScratch  = (LVM_INT16 *)pInstance->MemoryTable.Region[LVCS_MEMREGION_TEMPORARY_FAST].pBaseAddress;


    /*
     * Copy the data to the output in outplace processing
     */
    if (pInData != pOutData)
    {
        /*
         * Reverb not required so just copy the data
         */
        Copy_16((LVM_INT16 *)pInData,                                       /* Source */
                (LVM_INT16 *)pOutData,                                      /* Destination */
                (LVM_INT16)(2*NumSamples));                                 /* Left and right */
    }


    /*
     * Check if the reverb is required
     */
    if (((pInstance->Params.SpeakerType == LVCS_HEADPHONE) ||           /* Disable when CS4MS in stereo mode */
         (pInstance->Params.SpeakerType == LVCS_EX_HEADPHONES) ||
         (pInstance->Params.SourceFormat != LVCS_STEREO))  &&
        ((pInstance->Params.OperatingMode & LVCS_REVERBSWITCH) !=0))    /* For validation testing */
    {
        /********************************************************************************/
        /*                                                                              */
        /* Copy the input data to scratch memory and filter it                          */
        /*                                                                              */
        /********************************************************************************/

        /*
         * Copy the input data to the scratch memory
         */
        Copy_16((LVM_INT16 *)pInData,                                     /* Source */
                (LVM_INT16 *)pScratch,                                    /* Destination */
                (LVM_INT16)(2*NumSamples));                               /* Left and right */


        /*
         * Filter the data
         */
        (pConfig->pBiquadCallBack)((Biquad_Instance_t*)&pCoefficients->ReverbBiquadInstance,
                                   (LVM_INT16 *)pScratch,
                                   (LVM_INT16 *)pScratch,
                                   (LVM_INT16)NumSamples);

        Mult3s_16x16( (LVM_INT16 *)pScratch,
                      pConfig->ReverbLevel,
                      (LVM_INT16 *)pScratch,
                      (LVM_INT16)(2*NumSamples));


        /*
         * Apply the delay mix
         */
        DelayMix_16x16((LVM_INT16 *)pScratch,
                       &pConfig->StereoSamples[0],
                       pConfig->DelaySize,
                       pOutData,
                       &pConfig->DelayOffset,
                       (LVM_INT16)NumSamples);


    }

    return(LVCS_SUCCESS);
}





