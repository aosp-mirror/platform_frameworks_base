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

/**********************************************************************************
   FUNCTION LVCore_MIXHARD_2ST_D16C31_SAT
***********************************************************************************/

void LVC_Core_MixHard_2St_D16C31_SAT( LVMixer3_st *ptrInstance1,
                                    LVMixer3_st         *ptrInstance2,
                                    const LVM_INT16     *src1,
                                    const LVM_INT16     *src2,
                                          LVM_INT16     *dst,
                                          LVM_INT16     n)
{
    LVM_INT32  Temp;
    LVM_INT16 ii;
    LVM_INT16 Current1Short;
    LVM_INT16 Current2Short;
    Mix_Private_st  *pInstance1=(Mix_Private_st *)(ptrInstance1->PrivateParams);
    Mix_Private_st  *pInstance2=(Mix_Private_st *)(ptrInstance2->PrivateParams);


    Current1Short = (LVM_INT16)(pInstance1->Current >> 16);
    Current2Short = (LVM_INT16)(pInstance2->Current >> 16);

    for (ii = n; ii != 0; ii--){
        Temp = (((LVM_INT32)*(src1++) * (LVM_INT32)Current1Short)>>15) +
               (((LVM_INT32)*(src2++) * (LVM_INT32)Current2Short)>>15);
        if (Temp > 0x00007FFF)
            *dst++ = 0x7FFF;
        else if (Temp < -0x00008000)
            *dst++ = - 0x8000;
        else
            *dst++ = (LVM_INT16)Temp;
    }
}


/**********************************************************************************/
