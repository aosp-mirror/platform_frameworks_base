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

#include "LVPSA_QPD.h"

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_QPD_Init                                              */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Initialize a quasi peak filter instance.                                        */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pQPD_State          Pointer to the filter state                                 */
/*   pTaps               Pointer to the filter's taps                               */
/*   pCoef               Pointer to the filter's coefficients                       */
/*                                                                                  */
/* RETURNS:     void                                                                */
/*                                                                                  */
/************************************************************************************/
void LVPSA_QPD_Init (   pQPD_State_t       pQPD_State,
                        QPD_Taps_t        *pTaps,
                        QPD_C32_Coefs     *pCoef     )
{
    pQPD_State->pDelay  = pTaps->Storage;
    pQPD_State->Coefs[0]  = pCoef->KP;
    pQPD_State->Coefs[1]  = pCoef->KM;
}
