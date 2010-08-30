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

/**********************************************************************************
   FUNCTION LVCore_MIXSOFT_1ST_D16C31_WRA
***********************************************************************************/

void LVC_Core_MixInSoft_D16C31_SAT( LVMixer3_st *ptrInstance,
                                    const LVM_INT16     *src,
                                          LVM_INT16     *dst,
                                          LVM_INT16     n)
{

    LVM_INT16   OutLoop;
    LVM_INT16   InLoop;
    LVM_INT16   CurrentShort;
    LVM_INT32   ii,jj;
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
                Temp = ((LVM_INT32)*dst) + (((LVM_INT32)*(src++) * CurrentShort)>>15);      /* Q15 + Q15*Q15>>15 into Q15 */
                if (Temp > 0x00007FFF)
                    *dst++ = 0x7FFF;
                else if (Temp < -0x00008000)
                    *dst++ = - 0x8000;
                else
                    *dst++ = (LVM_INT16)Temp;
            }
        }

        for (ii = InLoop; ii != 0; ii--){
            ADD2_SAT_32x32(Current,Delta,Temp);                                      /* Q31 + Q31 into Q31*/
            Current=Temp;
            if (Current > Target)
                Current = Target;

            CurrentShort = (LVM_INT16)(Current>>16);                                 /* From Q31 to Q15*/

            for (jj = 4; jj!=0 ; jj--){
                Temp = ((LVM_INT32)*dst) + (((LVM_INT32)*(src++) * CurrentShort)>>15);      /* Q15 + Q15*Q15>>15 into Q15 */
                if (Temp > 0x00007FFF)
                    *dst++ = 0x7FFF;
                else if (Temp < -0x00008000)
                    *dst++ = - 0x8000;
                else
                    *dst++ = (LVM_INT16)Temp;
            }
        }
    }
    else{
        if (OutLoop){
            Current -= Delta;                                                        /* Q31 + Q31 into Q31*/
            if (Current < Target)
                Current = Target;

            CurrentShort = (LVM_INT16)(Current>>16);                                 /* From Q31 to Q15*/

            for (ii = OutLoop; ii != 0; ii--){
                Temp = ((LVM_INT32)*dst) + (((LVM_INT32)*(src++) * CurrentShort)>>15);      /* Q15 + Q15*Q15>>15 into Q15 */
                if (Temp > 0x00007FFF)
                    *dst++ = 0x7FFF;
                else if (Temp < -0x00008000)
                    *dst++ = - 0x8000;
                else
                    *dst++ = (LVM_INT16)Temp;
            }
        }

        for (ii = InLoop; ii != 0; ii--){
            Current -= Delta;                                                        /* Q31 + Q31 into Q31*/
            if (Current < Target)
                Current = Target;

            CurrentShort = (LVM_INT16)(Current>>16);                                 /* From Q31 to Q15*/

            for (jj = 4; jj!=0 ; jj--){
                Temp = ((LVM_INT32)*dst) + (((LVM_INT32)*(src++) * CurrentShort)>>15);      /* Q15 + Q15*Q15>>15 into Q15 */
                if (Temp > 0x00007FFF)
                    *dst++ = 0x7FFF;
                else if (Temp < -0x00008000)
                    *dst++ = - 0x8000;
                else
                    *dst++ = (LVM_INT16)Temp;
            }
        }
    }
    pInstance->Current=Current;
}


/**********************************************************************************/
