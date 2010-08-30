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
#include "LVCS_StereoEnhancer.h"
#include "VectorArithmetic.h"
#include "LVCS_Tables.h"

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                LVCS_StereoEnhanceInit                                  */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Initialises the stereo enhancement module based on the sample rate.             */
/*                                                                                  */
/*  The function selects the coefficients for the filters and clears the data       */
/*  history. It is also used for re-initialisation when one of the system control   */
/*  parameters changes but will only change the coefficients and clear the history  */
/*  if the sample rate or speaker type has changed.                                 */
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

LVCS_ReturnStatus_en LVCS_SEnhancerInit(LVCS_Handle_t       hInstance,
                                        LVCS_Params_t       *pParams)
{

    LVM_UINT16              Offset;
    LVCS_Instance_t         *pInstance = (LVCS_Instance_t  *)hInstance;
    LVCS_StereoEnhancer_t   *pConfig   = (LVCS_StereoEnhancer_t *)&pInstance->StereoEnhancer;
    LVCS_Data_t             *pData     = (LVCS_Data_t *)pInstance->MemoryTable.Region[LVCS_MEMREGION_PERSISTENT_FAST_DATA].pBaseAddress;
    LVCS_Coefficient_t      *pCoefficient = (LVCS_Coefficient_t *)pInstance->MemoryTable.Region[LVCS_MEMREGION_PERSISTENT_FAST_COEF].pBaseAddress;
    FO_C16_Coefs_t          CoeffsMid;
    BQ_C16_Coefs_t          CoeffsSide;
    const BiquadA012B12CoefsSP_t *pSESideCoefs;

    /*
     * If the sample rate or speaker type has changed update the filters
     */
    if ((pInstance->Params.SampleRate != pParams->SampleRate) ||
        (pInstance->Params.SpeakerType != pParams->SpeakerType))
    {
        /*
         * Set the filter coefficients based on the sample rate
         */
        /* Mid filter */
        Offset = (LVM_UINT16)pParams->SampleRate;

        /* Convert incoming coefficients to the required format/ordering */
        CoeffsMid.A0 = (LVM_INT16) LVCS_SEMidCoefTable[Offset].A0;
        CoeffsMid.A1 = (LVM_INT16) LVCS_SEMidCoefTable[Offset].A1;
        CoeffsMid.B1 = (LVM_INT16)-LVCS_SEMidCoefTable[Offset].B1;

        /* Clear the taps */
        LoadConst_16(0,                                                                 /* Value */
                     (void *)&pData->SEBiquadTapsMid,              /* Destination Cast to void:\
                                                                      no dereferencing in function*/
                     (LVM_UINT16)(sizeof(pData->SEBiquadTapsMid)/sizeof(LVM_UINT16)));  /* Number of words */

        FO_1I_D16F16Css_TRC_WRA_01_Init(&pCoefficient->SEBiquadInstanceMid,
                                        &pData->SEBiquadTapsMid,
                                        &CoeffsMid);

        /* Callbacks */
        if(LVCS_SEMidCoefTable[Offset].Scale==15)
        {
            pConfig->pBiquadCallBack_Mid  = FO_1I_D16F16C15_TRC_WRA_01;
        }

        Offset = (LVM_UINT16)(pParams->SampleRate);
        pSESideCoefs = (BiquadA012B12CoefsSP_t*)&LVCS_SESideCoefTable[0];

        /* Side filter */
        /* Convert incoming coefficients to the required format/ordering */
        CoeffsSide.A0 = (LVM_INT16) pSESideCoefs[Offset].A0;
        CoeffsSide.A1 = (LVM_INT16) pSESideCoefs[Offset].A1;
        CoeffsSide.A2 = (LVM_INT16) pSESideCoefs[Offset].A2;
        CoeffsSide.B1 = (LVM_INT16)-pSESideCoefs[Offset].B1;
        CoeffsSide.B2 = (LVM_INT16)-pSESideCoefs[Offset].B2;

        /* Clear the taps */
        LoadConst_16(0,                                                                 /* Value */
                     (void *)&pData->SEBiquadTapsSide,             /* Destination Cast to void:\
                                                                      no dereferencing in function*/
                     (LVM_UINT16)(sizeof(pData->SEBiquadTapsSide)/sizeof(LVM_UINT16))); /* Number of words */


        /* Callbacks */
        switch(pSESideCoefs[Offset].Scale)
        {
            case 14:
                BQ_1I_D16F32Css_TRC_WRA_01_Init(&pCoefficient->SEBiquadInstanceSide,
                                                &pData->SEBiquadTapsSide,
                                                &CoeffsSide);

                pConfig->pBiquadCallBack_Side  = BQ_1I_D16F32C14_TRC_WRA_01;
                break;
            case 15:
                BQ_1I_D16F16Css_TRC_WRA_01_Init(&pCoefficient->SEBiquadInstanceSide,
                                                &pData->SEBiquadTapsSide,
                                                &CoeffsSide);

                pConfig->pBiquadCallBack_Side  = BQ_1I_D16F16C15_TRC_WRA_01;
                break;
        }

    }


    return(LVCS_SUCCESS);
}

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                LVCS_StereoEnhance                                      */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Enhance the stereo image in the input samples based on the following block      */
/*  diagram:                                                                        */
/*                                                                                  */
/*                               ________                                           */
/*          ________            |        |          ________                        */
/*         |        |  Middle   | Treble |         |        |                       */
/*         |        |---------->| Boost  |-------->|        |                       */
/*         | Stereo |           |________|         | M & S  |                       */
/*      -->|   to   |            ________          |   to   |-->                    */
/*         | M & S  |  Side     |        |         | Stereo |                       */
/*         |        |---------->| Side   |-------->|        |                       */
/*         |________|           | Boost  |         |________|                       */
/*                              |________|                                          */
/*                                                                                  */
/*                                                                                  */
/*  If the input signal is a mono signal there will be no side signal and hence     */
/*  the side filter will not be run. In mobile speaker mode the middle filter is    */
/*  not required and the Trebble boost filter is replaced by a simple gain block.   */
/*                                                                                  */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance               Instance Handle                                         */
/*  pInData                 Pointer to the input data                               */
/*  pOutData                Pointer to the output data                              */
/*  NumSamples              Number of samples to process                            */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVCS_Success            Always succeeds                                         */
/*                                                                                  */
/* NOTES:                                                                           */
/*  1.  The side filter is not used in Mobile Speaker mode                          */
/*                                                                                  */
/************************************************************************************/

LVCS_ReturnStatus_en LVCS_StereoEnhancer(LVCS_Handle_t          hInstance,
                                         const LVM_INT16        *pInData,
                                         LVM_INT16              *pOutData,
                                         LVM_UINT16             NumSamples)
{

    LVCS_Instance_t         *pInstance = (LVCS_Instance_t  *)hInstance;
    LVCS_StereoEnhancer_t   *pConfig   = (LVCS_StereoEnhancer_t *)&pInstance->StereoEnhancer;
    LVCS_Coefficient_t      *pCoefficient = (LVCS_Coefficient_t *)pInstance->MemoryTable.Region[LVCS_MEMREGION_PERSISTENT_FAST_COEF].pBaseAddress;
    LVM_INT16               *pScratch  = (LVM_INT16 *)pInstance->MemoryTable.Region[LVCS_MEMREGION_TEMPORARY_FAST].pBaseAddress;

    /*
     * Check if the Stereo Enhancer is enabled
     */
    if ((pInstance->Params.OperatingMode & LVCS_STEREOENHANCESWITCH) != 0)
        {
        /*
         * Convert from stereo to middle and side
         */
        From2iToMS_16x16(pInData,
                         pScratch,
                         pScratch+NumSamples,
                         (LVM_INT16)NumSamples);

        /*
         * Apply filter to the middle signal
         */
        if (pInstance->OutputDevice == LVCS_HEADPHONE)
        {
            (pConfig->pBiquadCallBack_Mid)((Biquad_Instance_t*)&pCoefficient->SEBiquadInstanceMid,
                                           (LVM_INT16 *)pScratch,
                                           (LVM_INT16 *)pScratch,
                                           (LVM_INT16)NumSamples);
        }
        else
        {
            Mult3s_16x16(pScratch,              /* Source */
                         (LVM_INT16)pConfig->MidGain,      /* Gain */
                         pScratch,              /* Destination */
                         (LVM_INT16)NumSamples);           /* Number of samples */
        }

        /*
         * Apply the filter the side signal only in stereo mode for headphones
         * and in all modes for mobile speakers
         */
        if (pInstance->Params.SourceFormat == LVCS_STEREO)
        {
            (pConfig->pBiquadCallBack_Side)((Biquad_Instance_t*)&pCoefficient->SEBiquadInstanceSide,
                                            (LVM_INT16 *)(pScratch + NumSamples),
                                            (LVM_INT16 *)(pScratch + NumSamples),
                                            (LVM_INT16)NumSamples);
        }

        /*
         * Convert from middle and side to stereo
         */
        MSTo2i_Sat_16x16(pScratch,
                         pScratch+NumSamples,
                         pOutData,
                         (LVM_INT16)NumSamples);

    }
    else
    {
        /*
         * The stereo enhancer is disabled so just copy the data
         */
        Copy_16((LVM_INT16 *)pInData,           /* Source */
                (LVM_INT16 *)pOutData,          /* Destination */
                (LVM_INT16)(2*NumSamples));     /* Left and right */

    }

    return(LVCS_SUCCESS);
}




