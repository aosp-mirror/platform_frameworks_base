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

void Core_MixInSoft_D32C31_SAT(     Mix_1St_Cll_t       *pInstance,
                                    const LVM_INT32     *src,
                                          LVM_INT32     *dst,
                                          LVM_INT16     n)
{
    LVM_INT32    Temp1,Temp2,Temp3;
    LVM_INT16     OutLoop;
    LVM_INT16     InLoop;
    LVM_INT32    TargetTimesOneMinAlpha;
    LVM_INT32    CurrentTimesAlpha;
    LVM_INT16     ii,jj;
    LVM_INT16   CurrentShort;

    InLoop = (LVM_INT16)(n >> 2); /* Process per 4 samples */
    OutLoop = (LVM_INT16)(n - (InLoop << 2));

    MUL32x32INTO32((0x7FFFFFFF-pInstance->Alpha),pInstance->Target,TargetTimesOneMinAlpha,31); /* Q31 * Q0 in Q0 */
    if (pInstance->Target >= pInstance->Current){
         TargetTimesOneMinAlpha +=2; /* Ceil*/
    }

    if (OutLoop){
        MUL32x32INTO32(pInstance->Current,pInstance->Alpha,CurrentTimesAlpha,31);       /* Q0 * Q31 in Q0 */
        pInstance->Current = TargetTimesOneMinAlpha + CurrentTimesAlpha;                /* Q0 + Q0 into Q0*/
        CurrentShort = (LVM_INT16)(pInstance->Current>>16);                             /* From Q31 to Q15*/

        for (ii = OutLoop; ii != 0; ii--){
        Temp1=*src++;
        Temp2=*dst;
        MUL32x16INTO32(Temp1,CurrentShort,Temp3,15)
        Temp1=(Temp2>>1)+(Temp3>>1);

        if (Temp1 > 0x3FFFFFFF)
            Temp1 = 0x7FFFFFFF;
        else if (Temp1 < - 0x40000000)
            Temp1 =  0x80000000;
        else
            Temp1=(Temp1<<1);
            *dst++ = Temp1;
        }
    }

    for (ii = InLoop; ii != 0; ii--){
        MUL32x32INTO32(pInstance->Current,pInstance->Alpha,CurrentTimesAlpha,31);       /* Q0 * Q31 in Q0 */
        pInstance->Current = TargetTimesOneMinAlpha + CurrentTimesAlpha;                /* Q0 + Q0 into Q0*/
        CurrentShort = (LVM_INT16)(pInstance->Current>>16);                             /* From Q31 to Q15*/

        for (jj = 4; jj!=0 ; jj--){
        Temp1=*src++;
        Temp2=*dst;
        MUL32x16INTO32(Temp1,CurrentShort,Temp3,15)
        Temp1=(Temp2>>1)+(Temp3>>1);

        if (Temp1 > 0x3FFFFFFF)
            Temp1 = 0x7FFFFFFF;
        else if (Temp1 < - 0x40000000)
            Temp1 =  0x80000000;
        else
            Temp1=(Temp1<<1);
            *dst++ = Temp1;
        }
    }
}


/**********************************************************************************/
