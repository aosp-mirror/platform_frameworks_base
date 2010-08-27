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

#include "LVC_Mixer_Private.h"
#include "LVM_Macros.h"
#include "ScalarArithmetic.h"

/**********************************************************************************
   FUNCTION LVCore_MIXSOFT_1ST_D16C31_WRA
***********************************************************************************/

void LVC_Core_MixSoft_1St_D16C31_WRA( LVMixer3_st *ptrInstance,
                                    const LVM_INT16     *src,
                                          LVM_INT16     *dst,
                                          LVM_INT16     n)
{
    LVM_INT16   OutLoop;
    LVM_INT16   InLoop;
    LVM_INT16   CurrentShort;
    LVM_INT32   ii;
    Mix_Private_st  *pInstance=(Mix_Private_st *)(ptrInstance->PrivateParams);
    LVM_INT32   Delta=pInstance->Delta;
    LVM_INT32   Current=pInstance->Current;
    LVM_INT32   Target=pInstance->Target;
    LVM_INT32   Temp;

    InLoop = (LVM_INT16)(n >> 2); /* Process per 4 samples */
    OutLoop = (LVM_INT16)(n - (InLoop << 2));

    if(Current<Target){
        if (OutLoop){
            ADD2_SAT_32x32(Current,Delta,Temp);                                      /* Q31 + Q31 into Q31*/
            Current=Temp;
            if (Current > Target)
                Current = Target;

            CurrentShort = (LVM_INT16)(Current>>16);                                 /* From Q31 to Q15*/

            for (ii = OutLoop; ii != 0; ii--){
                *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShort)>>15);    /* Q15*Q15>>15 into Q15 */
            }
        }

        for (ii = InLoop; ii != 0; ii--){
            ADD2_SAT_32x32(Current,Delta,Temp);                                      /* Q31 + Q31 into Q31*/
            Current=Temp;
            if (Current > Target)
                Current = Target;

            CurrentShort = (LVM_INT16)(Current>>16);                                 /* From Q31 to Q15*/

            *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShort)>>15);    /* Q15*Q15>>15 into Q15 */
            *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShort)>>15);
            *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShort)>>15);
            *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShort)>>15);
        }
    }
    else{
        if (OutLoop){
            Current -= Delta;                                                        /* Q31 + Q31 into Q31*/
            if (Current < Target)
                Current = Target;

            CurrentShort = (LVM_INT16)(Current>>16);                                 /* From Q31 to Q15*/

            for (ii = OutLoop; ii != 0; ii--){
                *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShort)>>15);    /* Q15*Q15>>15 into Q15 */
            }
        }

        for (ii = InLoop; ii != 0; ii--){
            Current -= Delta;                                                        /* Q31 + Q31 into Q31*/
            if (Current < Target)
                Current = Target;

            CurrentShort = (LVM_INT16)(Current>>16);                                 /* From Q31 to Q15*/

            *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShort)>>15);    /* Q15*Q15>>15 into Q15 */
            *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShort)>>15);
            *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShort)>>15);
            *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShort)>>15);
        }
    }
    pInstance->Current=Current;
}


/**********************************************************************************/
