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

#include "LVM_Private.h"
#include "LVM_Tables.h"

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_GetSpectrum                                             */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/* This function is used to retrieve Spectral information at a given Audio time         */
/* for display usage                                                                    */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance Handle                                             */
/*  pCurrentPeaks           Pointer to location where currents peaks are to be saved    */
/*  pPastPeaks              Pointer to location where past peaks are to be saved        */
/*  AudioTime               Audio time at which the spectral information is needed      */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Succeeded                                                   */
/*  LVM_NULLADDRESS         If any of input addresses are NULL                          */
/*  LVM_WRONGAUDIOTIME      Failure due to audio time error                             */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. This function may be interrupted by the LVM_Process function                     */
/*                                                                                      */
/****************************************************************************************/
LVM_ReturnStatus_en LVM_GetSpectrum(
                                    LVM_Handle_t            hInstance,
                                    LVM_UINT8               *pCurrentPeaks,
                                    LVM_UINT8               *pPastPeaks,
                                    LVM_INT32               AudioTime
                                    )
{
    LVM_Instance_t           *pInstance   = (LVM_Instance_t  *)hInstance;

    pLVPSA_Handle_t        *hPSAInstance;
    LVPSA_RETURN           LVPSA_Status;


    if(pInstance == LVM_NULL)
    {
        return LVM_NULLADDRESS;
    }

    /*If PSA is not included at the time of instance creation, return without any processing*/
    if(pInstance->InstParams.PSA_Included!=LVM_PSA_ON)
    {
        return LVM_SUCCESS;
    }

    hPSAInstance = pInstance->hPSAInstance;

    if((pCurrentPeaks == LVM_NULL) ||
        (pPastPeaks == LVM_NULL))
    {
        return LVM_NULLADDRESS;
    }


    /*
     * Update new parameters if necessary
     */
    if (pInstance->ControlPending == LVM_TRUE)
    {
        LVM_ApplyNewSettings(hInstance);
    }

    /* If PSA module is disabled, do nothing */
    if(pInstance->Params.PSA_Enable==LVM_PSA_OFF)
    {
        return LVM_ALGORITHMDISABLED;
    }

    LVPSA_Status = LVPSA_GetSpectrum(hPSAInstance,
                            (LVPSA_Time) (AudioTime),
                            (LVM_UINT8*) pCurrentPeaks,
                            (LVM_UINT8*) pPastPeaks );

    if(LVPSA_Status != LVPSA_OK)
    {
        if(LVPSA_Status == LVPSA_ERROR_WRONGTIME)
        {
            return (LVM_ReturnStatus_en) LVM_WRONGAUDIOTIME;
        }
        else
        {
            return (LVM_ReturnStatus_en) LVM_NULLADDRESS;
        }
    }

    return(LVM_SUCCESS);
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVM_SetVolumeNoSmoothing                                    */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/* This function is used to set output volume without any smoothing                     */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance Handle                                             */
/*  pParams                 Control Parameters, only volume value is used here          */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_SUCCESS             Succeeded                                                   */
/*  LVM_NULLADDRESS         If any of input addresses are NULL                          */
/*  LVM_OUTOFRANGE          When any of the control parameters are out of range         */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. This function may be interrupted by the LVM_Process function                     */
/*                                                                                      */
/****************************************************************************************/
LVM_ReturnStatus_en LVM_SetVolumeNoSmoothing( LVM_Handle_t           hInstance,
                                              LVM_ControlParams_t    *pParams)
{
    LVM_Instance_t      *pInstance =(LVM_Instance_t  *)hInstance;
    LVM_ReturnStatus_en Error;

    /*Apply new controls*/
    Error = LVM_SetControlParameters(hInstance,pParams);
    pInstance->NoSmoothVolume = LVM_TRUE;
    return Error;
}

