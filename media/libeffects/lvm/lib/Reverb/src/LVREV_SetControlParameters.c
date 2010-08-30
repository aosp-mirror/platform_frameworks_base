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
/*  Includes                                                                            */
/*                                                                                      */
/****************************************************************************************/
#include "LVREV_Private.h"

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVREV_SetControlParameters                                  */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Sets or changes the LVREV module parameters.                                        */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pNewParams              Pointer to a parameter structure                            */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVM_Success             Succeeded                                                   */
/*  LVREV_NULLADDRESS       When hInstance or pNewParams is NULL                        */
/*  LVREV_OUTOFRANGE        When any of the new parameters is out of range              */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVREV_Process function                  */
/*                                                                                      */
/****************************************************************************************/
LVREV_ReturnStatus_en LVREV_SetControlParameters(LVREV_Handle_t           hInstance,
                                                 LVREV_ControlParams_st   *pNewParams)
{

    LVREV_Instance_st     *pLVREV_Private = (LVREV_Instance_st *)hInstance;


    /*
     * Check for error conditions
     */
    if((hInstance == LVM_NULL) || (pNewParams == LVM_NULL))
    {
        return LVREV_NULLADDRESS;
    }

    /*
     * Check all new control parameters are in range
     */
    if(    ((pNewParams->OperatingMode != LVM_MODE_OFF) && (pNewParams->OperatingMode != LVM_MODE_ON))                                         ||
        ((pNewParams->SampleRate != LVM_FS_8000) && (pNewParams->SampleRate != LVM_FS_11025) && (pNewParams->SampleRate != LVM_FS_12000)       &&
        (pNewParams->SampleRate != LVM_FS_16000) && (pNewParams->SampleRate != LVM_FS_22050) && (pNewParams->SampleRate != LVM_FS_24000)       &&
        (pNewParams->SampleRate != LVM_FS_32000) && (pNewParams->SampleRate != LVM_FS_44100) && (pNewParams->SampleRate != LVM_FS_48000))      ||
        ((pNewParams->SourceFormat != LVM_STEREO) && (pNewParams->SourceFormat != LVM_MONOINSTEREO) && (pNewParams->SourceFormat != LVM_MONO)) )
    {
        return (LVREV_OUTOFRANGE);
    }


    if (pNewParams->Level > LVREV_MAX_LEVEL)
    {
        return LVREV_OUTOFRANGE;
    }

    if ((pNewParams->LPF < LVREV_MIN_LPF_CORNER) || (pNewParams->LPF > LVREV_MAX_LPF_CORNER))
    {
        return LVREV_OUTOFRANGE;
    }

    if ((pNewParams->HPF < LVREV_MIN_HPF_CORNER) || (pNewParams->HPF > LVREV_MAX_HPF_CORNER))
    {
        return LVREV_OUTOFRANGE;
    }

    if (pNewParams->T60 > LVREV_MAX_T60)
    {
        return LVREV_OUTOFRANGE;
    }

    if (pNewParams->Density > LVREV_MAX_DENSITY)
    {
        return LVREV_OUTOFRANGE;
    }

    if (pNewParams->Damping > LVREV_MAX_DAMPING)
    {
        return LVREV_OUTOFRANGE;
    }

    if (pNewParams->RoomSize > LVREV_MAX_ROOMSIZE)
    {
        return LVREV_OUTOFRANGE;
    }



    /*
     * Copy the new parameters and set the flag to indicate they are available
     */
    pLVREV_Private->NewParams       = *pNewParams;
    pLVREV_Private->bControlPending = LVM_TRUE;

    return LVREV_SUCCESS;
}

/* End of file */
