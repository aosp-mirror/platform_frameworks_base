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
#include "DC_2I_D16_TRC_WRA_01_Private.h"

void  DC_2I_D16_TRC_WRA_01_Init(Biquad_Instance_t   *pInstance)
{
    PFilter_State pBiquadState  = (PFilter_State) pInstance;
    pBiquadState->LeftDC        = 0;
    pBiquadState->RightDC       = 0;
}

