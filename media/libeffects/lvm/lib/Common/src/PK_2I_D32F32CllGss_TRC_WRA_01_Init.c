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

#include "BIQUAD.h"
#include "PK_2I_D32F32CllGss_TRC_WRA_01_Private.h"


void  PK_2I_D32F32CllGss_TRC_WRA_01_Init(Biquad_Instance_t         *pInstance,
                                         Biquad_2I_Order2_Taps_t   *pTaps,
                                         PK_C32_Coefs_t            *pCoef)
{
  PFilter_State pBiquadState = (PFilter_State) pInstance;
  pBiquadState->pDelays       =(LVM_INT32 *) pTaps;

  pBiquadState->coefs[0]=pCoef->A0;

  pBiquadState->coefs[1]=pCoef->B2;

  pBiquadState->coefs[2]=pCoef->B1;

  pBiquadState->coefs[3]=pCoef->G;

}

