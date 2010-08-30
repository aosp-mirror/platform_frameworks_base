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
/* FUNCTION:                LVREV_GetControlParameters                                  */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Request the LVREV module control parameters. The current parameter set is returned  */
/*  via the parameter pointer.                                                          */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pControlParams          Pointer to an empty parameter structure                     */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVREV_Success           Succeeded                                                   */
/*  LVREV_NULLADDRESS       When hInstance or pControlParams is NULL                    */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1.  This function may be interrupted by the LVREV_Process function                  */
/*                                                                                      */
/****************************************************************************************/
LVREV_ReturnStatus_en LVREV_GetControlParameters(LVREV_Handle_t           hInstance,
                                                 LVREV_ControlParams_st   *pControlParams)
{

    LVREV_Instance_st  *pLVREV_Private = (LVREV_Instance_st *)hInstance;


    /*
     * Check for error conditions
     */
    if((hInstance == LVM_NULL) || (pControlParams == LVM_NULL))
    {
        return LVREV_NULLADDRESS;
    }

    /*
     * Return the current parameters
     */
    *pControlParams = pLVREV_Private->NewParams;

    return LVREV_SUCCESS;
}

/* End of file */
