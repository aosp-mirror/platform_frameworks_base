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

/**********************************************************************************
   INCLUDE FILES
***********************************************************************************/

#include "Mixer_private.h"
#include "LVM_Macros.h"

/**********************************************************************************
   FUNCTION CORE_MIXSOFT_1ST_D32C31_WRA
***********************************************************************************/

void Core_MixSoft_1St_D32C31_WRA(   Mix_1St_Cll_t       *pInstance,
                                    const LVM_INT32     *src,
                                          LVM_INT32     *dst,
                                          LVM_INT16     n)
{
    LVM_INT32  Temp1,Temp2;
    LVM_INT16 OutLoop;
    LVM_INT16 InLoop;
    LVM_INT32  TargetTimesOneMinAlpha;
    LVM_INT32  CurrentTimesAlpha;
    LVM_INT16 CurrentShort;
    LVM_INT16 ii;

    InLoop = (LVM_INT16)(n >> 2); /* Process per 4 samples */
    OutLoop = (LVM_INT16)(n - (InLoop << 2));

    MUL32x32INTO32((0x7FFFFFFF-pInstance->Alpha),pInstance->Target,TargetTimesOneMinAlpha,31) /* Q31 * Q31 in Q31 */
    if (pInstance->Target >= pInstance->Current)
    {
         TargetTimesOneMinAlpha +=2; /* Ceil*/
    }

    if (OutLoop!=0)
    {
        MUL32x32INTO32(pInstance->Current,pInstance->Alpha,CurrentTimesAlpha,31)  /* Q31 * Q31 in Q31 */
        pInstance->Current = TargetTimesOneMinAlpha + CurrentTimesAlpha;          /* Q31 + Q31 into Q31*/
        CurrentShort = (LVM_INT16)(pInstance->Current>>16);                       /* From Q31 to Q15*/

        for (ii = OutLoop; ii != 0; ii--)
        {
            Temp1=*src;
            src++;

            MUL32x16INTO32(Temp1,CurrentShort,Temp2,15)
            *dst = Temp2;
            dst++;
        }
    }

    for (ii = InLoop; ii != 0; ii--)
    {
        MUL32x32INTO32(pInstance->Current,pInstance->Alpha,CurrentTimesAlpha,31)  /* Q31 * Q31 in Q31 */
        pInstance->Current = TargetTimesOneMinAlpha + CurrentTimesAlpha;          /* Q31 + Q31 into Q31*/
        CurrentShort = (LVM_INT16)(pInstance->Current>>16);                       /* From Q31 to Q15*/
            Temp1=*src;
            src++;

            MUL32x16INTO32(Temp1,CurrentShort,Temp2,15)
            *dst = Temp2;
            dst++;

            Temp1=*src;
            src++;

            MUL32x16INTO32(Temp1,CurrentShort,Temp2,15)
            *dst = Temp2;
            dst++;

            Temp1=*src;
            src++;

            MUL32x16INTO32(Temp1,CurrentShort,Temp2,15)
            *dst = Temp2;
            dst++;

            Temp1=*src;
            src++;
            MUL32x16INTO32(Temp1,CurrentShort,Temp2,15)
            *dst = Temp2;
            dst++;
    }
}


/**********************************************************************************/
