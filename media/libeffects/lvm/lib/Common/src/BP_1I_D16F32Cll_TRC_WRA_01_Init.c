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
#include "BP_1I_D16F32Cll_TRC_WRA_01_Private.h"


/*-------------------------------------------------------------------------*/
/* FUNCTION:                                                               */
/*   BP_1I_D16F32Cll_TRC_WRA_01_Init                                       */
/*                                                                         */
/* DESCRIPTION:                                                            */
/*   These functions initializes a Band pass filter (BIQUAD)               */
/*   biquadratic Filter Sections.                                          */
/*                                                                         */
/* PARAMETERS:                                                             */
/*   pInstance    - output, returns the pointer to the State Variable      */
/*                   This state pointer must be passed to any subsequent   */
/*                   call to "Biquad" functions.                           */
/*   pTaps         - input, pointer to the taps memory                     */
/*   pCoef         - input, pointer to the coefficient structure           */
/*   N             - M coefficient factor of QM.N                          */
/*                                                                         */
/*        The coefficients are modified in the init() function such that lower               */
/*        half word is right shifted by one and most significant bit of the lower            */
/*        word is made to be zero.                                                           */
/*                                                                                           */
/*       Reason: For MIPS effciency,we are using DSP 32*16 multiplication                    */
/*       instruction. But we have 32*32 multiplication. This can be realized by two 32*16    */
/*       multiplication. But 16th bit in the 32 bit word is not a sign bit. So this is done  */
/*       by putting 16th bit to zero and lossing one bit precision by division of lower      */
/*       half word by 2.                                                                     */
/* RETURNS:                                                                */
/*   void return code                                                      */
/*-------------------------------------------------------------------------*/
void BP_1I_D16F32Cll_TRC_WRA_01_Init (   Biquad_Instance_t         *pInstance,
                                         Biquad_1I_Order2_Taps_t   *pTaps,
                                         BP_C32_Coefs_t            *pCoef)
{
  PFilter_State pBiquadState = (PFilter_State) pInstance;
  pBiquadState->pDelays       =(LVM_INT32 *) pTaps;

  pBiquadState->coefs[0] =  pCoef->A0;
  pBiquadState->coefs[1] =  pCoef->B2;
  pBiquadState->coefs[2] =  pCoef->B1;
}
/*-------------------------------------------------------------------------*/
/* End Of File: BP_1I_D16F32Cll_TRC_WRA_01_Init.c                              */

