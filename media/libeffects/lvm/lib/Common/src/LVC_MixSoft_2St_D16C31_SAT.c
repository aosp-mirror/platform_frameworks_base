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
#include "VectorArithmetic.h"

/**********************************************************************************
   FUNCTION LVC_MixSoft_2St_D16C31_SAT.c
***********************************************************************************/

void LVC_MixSoft_2St_D16C31_SAT( LVMixer3_2St_st *ptrInstance,
                                    const   LVM_INT16       *src1,
                                            LVM_INT16       *src2,
                                            LVM_INT16       *dst,
                                            LVM_INT16       n)
{
    Mix_Private_st  *pInstance1=(Mix_Private_st *)(ptrInstance->MixerStream[0].PrivateParams);
    Mix_Private_st  *pInstance2=(Mix_Private_st *)(ptrInstance->MixerStream[1].PrivateParams);

    if(n<=0)    return;

    /******************************************************************************
       SOFT MIXING
    *******************************************************************************/
    if ((pInstance1->Current == pInstance1->Target)&&(pInstance1->Current == 0)){
        LVC_MixSoft_1St_D16C31_SAT( (LVMixer3_1St_st *)(&ptrInstance->MixerStream[1]), src2, dst, n);
    }
    else if ((pInstance2->Current == pInstance2->Target)&&(pInstance2->Current == 0)){
        LVC_MixSoft_1St_D16C31_SAT( (LVMixer3_1St_st *)(&ptrInstance->MixerStream[0]), src1, dst, n);
    }
    else if ((pInstance1->Current != pInstance1->Target) || (pInstance2->Current != pInstance2->Target))
    {
        LVC_MixSoft_1St_D16C31_SAT((LVMixer3_1St_st *)(&ptrInstance->MixerStream[0]), src1, dst, n);
        LVC_MixInSoft_D16C31_SAT( (LVMixer3_1St_st *)(&ptrInstance->MixerStream[1]), src2, dst, n);
    }
    else{
        /******************************************************************************
           HARD MIXING
        *******************************************************************************/
        if(pInstance2->Shift!=0)
            Shift_Sat_v16xv16 ((LVM_INT16)pInstance2->Shift,src2,src2,n);
        if(pInstance1->Shift!=0)
        {
            Shift_Sat_v16xv16 ((LVM_INT16)pInstance1->Shift,src1,dst,n);
            LVC_Core_MixHard_2St_D16C31_SAT( &ptrInstance->MixerStream[0], &ptrInstance->MixerStream[1], dst, src2, dst, n);
        }
        else
            LVC_Core_MixHard_2St_D16C31_SAT( &ptrInstance->MixerStream[0], &ptrInstance->MixerStream[1], src1, src2, dst, n);
    }
}

/**********************************************************************************/
