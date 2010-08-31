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

#include "LVM_Types.h"
#include "LVM_Macros.h"
#include "LVC_Mixer_Private.h"


/************************************************************************/
/* FUNCTION:                                                            */
/*   LVMixer3_GetCurrent                                                */
/*                                                                      */
/* DESCRIPTION:                                                         */
/*  This function returns the CurrentGain in Q16.15 format              */
/*                                                                      */
/* RETURNS:                                                             */
/*  CurrentGain      - CurrentGain value in Q 16.15 format              */
/*                                                                      */
/************************************************************************/

LVM_INT32 LVC_Mixer_GetCurrent( LVMixer3_st *pStream)
{
    LVM_INT32       CurrentGain;
    Mix_Private_st  *pInstance=(Mix_Private_st *)pStream->PrivateParams;
    CurrentGain=pInstance->Current>>(16-pInstance->Shift);  // CurrentGain in Q16.15 format
    return CurrentGain;
}
