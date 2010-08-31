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

/*-------------------------------------------------------------------------*/
#include "BIQUAD.h"
#include "FO_1I_D16F16Css_TRC_WRA_01_Private.h"


/*-------------------------------------------------------------------------*/
/* FUNCTION:                                                               */
/*   FO_1I_D16F16Css_TRC_WRA_01_Init                                       */
/*                                                                         */
/* DESCRIPTION:                                                            */
/*   These functions initializes a BIQUAD filter defined as a cascade of   */
/*   biquadratic Filter Sections.                                          */
/*                                                                         */
/* PARAMETERS:                                                             */
/*   pInstance    - output, returns the pointer to the State Variable      */
/*                   This state pointer must be passed to any subsequent   */
/*                   call to "Biquad" functions.                           */
/*   pTaps         - input, pointer to the taps memory                     */
/*   pCoef         - input, pointer to the coefficient structure           */
/*   N             - M coefficient factor of QM.N                          */
/* RETURNS:                                                                */
/*   void return code                                                      */
/*-------------------------------------------------------------------------*/
void FO_1I_D16F16Css_TRC_WRA_01_Init(    Biquad_Instance_t         *pInstance,
                                         Biquad_1I_Order1_Taps_t   *pTaps,
                                         FO_C16_Coefs_t            *pCoef)
{
  LVM_INT16 temp;
  PFilter_State pBiquadState = (PFilter_State)  pInstance;
  pBiquadState->pDelays      =(LVM_INT32 *)     pTaps;

  temp=pCoef->A1;
  pBiquadState->coefs[0]=temp;
  temp=pCoef->A0;
  pBiquadState->coefs[1]=temp;
  temp=pCoef->B1;
  pBiquadState->coefs[2]=temp;
}
/*------------------------------------------------*/
/* End Of File: FO_1I_D16F16Css_TRC_WRA_01_Init.c */

