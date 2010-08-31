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
#include "LVCS_Equaliser.h"
#include "BIQUAD.h"
#include "VectorArithmetic.h"
#include "LVCS_Tables.h"

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                LVCS_EqualiserInit                                      */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Initialises the equaliser module                                                */
/*                                                                                  */
/*  The function selects the coefficients for the filters and clears the data       */
/*  history. It is also used for re-initialisation when one of the system control   */
/*  parameters changes but will only change the coefficients and clear the history  */
/*  if the sample rate or speaker type has changed.                                 */
/*                                                                                  */
/*  To avoid excessive testing during the sample processing the biquad type is      */
/*  set as a callback function in the init routine.                                 */
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

LVCS_ReturnStatus_en LVCS_EqualiserInit(LVCS_Handle_t       hInstance,
                                        LVCS_Params_t       *pParams)
{

    LVM_UINT16          Offset;
    LVCS_Instance_t     *pInstance = (LVCS_Instance_t  *)hInstance;
    LVCS_Equaliser_t    *pConfig   = (LVCS_Equaliser_t *)&pInstance->Equaliser;
    LVCS_Data_t         *pData     = (LVCS_Data_t *)pInstance->MemoryTable.Region[LVCS_MEMREGION_PERSISTENT_FAST_DATA].pBaseAddress;
    LVCS_Coefficient_t  *pCoefficients = (LVCS_Coefficient_t *)pInstance->MemoryTable.Region[LVCS_MEMREGION_PERSISTENT_FAST_COEF].pBaseAddress;
    BQ_C16_Coefs_t      Coeffs;
    const BiquadA012B12CoefsSP_t *pEqualiserCoefTable;

    /*
     * If the sample rate changes re-initialise the filters
     */
    if ((pInstance->Params.SampleRate != pParams->SampleRate) ||
        (pInstance->Params.SpeakerType != pParams->SpeakerType))
    {
        /*
         * Setup the filter coefficients and clear the history
         */
        Offset = (LVM_UINT16)(pParams->SampleRate + (pParams->SpeakerType * (1+LVM_FS_48000)));
        pEqualiserCoefTable = (BiquadA012B12CoefsSP_t*)&LVCS_EqualiserCoefTable[0];

        /* Left and right filters */
        /* Convert incoming coefficients to the required format/ordering */
        Coeffs.A0 = (LVM_INT16) pEqualiserCoefTable[Offset].A0;
        Coeffs.A1 = (LVM_INT16) pEqualiserCoefTable[Offset].A1;
        Coeffs.A2 = (LVM_INT16) pEqualiserCoefTable[Offset].A2;
        Coeffs.B1 = (LVM_INT16)-pEqualiserCoefTable[Offset].B1;
        Coeffs.B2 = (LVM_INT16)-pEqualiserCoefTable[Offset].B2;

        LoadConst_16((LVM_INT16)0,                                                       /* Value */
                     (void *)&pData->EqualiserBiquadTaps,   /* Destination Cast to void:\
                                                               no dereferencing in function*/
                     (LVM_UINT16)(sizeof(pData->EqualiserBiquadTaps)/sizeof(LVM_INT16)));    /* Number of words */

        BQ_2I_D16F32Css_TRC_WRA_01_Init(&pCoefficients->EqualiserBiquadInstance,
                                        &pData->EqualiserBiquadTaps,
                                        &Coeffs);

        /* Callbacks */
        switch(pEqualiserCoefTable[Offset].Scale)
        {
            case 13:
                pConfig->pBiquadCallBack  = BQ_2I_D16F32C13_TRC_WRA_01;
                break;
            case 14:
                pConfig->pBiquadCallBack  = BQ_2I_D16F32C14_TRC_WRA_01;
                break;
            case 15:
                pConfig->pBiquadCallBack  = BQ_2I_D16F32C15_TRC_WRA_01;
                break;
        }
    }

    return(LVCS_SUCCESS);
}

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:                LVCS_Equaliser                                          */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Apply the equaliser filter.                                                     */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance               Instance Handle                                         */
/*  pInputOutput            Pointer to the input/output buffer                      */
/*  NumSamples              The number of samples to process                        */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVCS_Success            Always succeeds                                         */
/*                                                                                  */
/* NOTES:                                                                           */
/*  1.  Always processes in place.                                                  */
/*                                                                                  */
/************************************************************************************/

LVCS_ReturnStatus_en LVCS_Equaliser(LVCS_Handle_t       hInstance,
                                    LVM_INT16           *pInputOutput,
                                    LVM_UINT16          NumSamples)
{

    LVCS_Instance_t     *pInstance = (LVCS_Instance_t  *)hInstance;
    LVCS_Equaliser_t    *pConfig   = (LVCS_Equaliser_t  *)&pInstance->Equaliser;
    LVCS_Coefficient_t  *pCoefficients = (LVCS_Coefficient_t *)pInstance->MemoryTable.Region[LVCS_MEMREGION_PERSISTENT_FAST_COEF].pBaseAddress;


    /*
     * Check if the equaliser is required
     */
    if ((pInstance->Params.OperatingMode & LVCS_EQUALISERSWITCH) != 0)
    {
        /* Apply filter to the left and right channels */
        (pConfig->pBiquadCallBack)((Biquad_Instance_t*)&pCoefficients->EqualiserBiquadInstance,
                                   (LVM_INT16 *)pInputOutput,
                                   (LVM_INT16 *)pInputOutput,
                                   (LVM_INT16)NumSamples);
    }

    return(LVCS_SUCCESS);
}

