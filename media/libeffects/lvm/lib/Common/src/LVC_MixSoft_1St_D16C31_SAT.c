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
#include "ScalarArithmetic.h"

/**********************************************************************************
   DEFINITIONS
***********************************************************************************/

#define TRUE          1
#define FALSE         0

/**********************************************************************************
   FUNCTION LVMixer3_MIXSOFT_1ST_D16C31_SAT
***********************************************************************************/

void LVC_MixSoft_1St_D16C31_SAT( LVMixer3_1St_st *ptrInstance,
                                  const LVM_INT16             *src,
                                        LVM_INT16             *dst,
                                        LVM_INT16             n)
{
    char        HardMixing = TRUE;
    LVM_INT32   TargetGain;
    Mix_Private_st  *pInstance=(Mix_Private_st *)(ptrInstance->MixerStream[0].PrivateParams);

    if(n<=0)    return;

    /******************************************************************************
       SOFT MIXING
    *******************************************************************************/
    if (pInstance->Current != pInstance->Target)
    {
        if(pInstance->Delta == 0x7FFFFFFF){
            pInstance->Current = pInstance->Target;
            TargetGain=pInstance->Target>>(16-pInstance->Shift);  // TargetGain in Q16.15 format
            LVC_Mixer_SetTarget(&(ptrInstance->MixerStream[0]),TargetGain);
        }else if (Abs_32(pInstance->Current-pInstance->Target) < pInstance->Delta){
            pInstance->Current = pInstance->Target; /* Difference is not significant anymore.  Make them equal. */
            TargetGain=pInstance->Target>>(16-pInstance->Shift);  // TargetGain in Q16.15 format
            LVC_Mixer_SetTarget(&(ptrInstance->MixerStream[0]),TargetGain);
        }else{
            /* Soft mixing has to be applied */
            HardMixing = FALSE;
            if(pInstance->Shift!=0){
                Shift_Sat_v16xv16 ((LVM_INT16)pInstance->Shift,src,dst,n);
                LVC_Core_MixSoft_1St_D16C31_WRA( &(ptrInstance->MixerStream[0]), dst, dst, n);
            }
            else
                LVC_Core_MixSoft_1St_D16C31_WRA( &(ptrInstance->MixerStream[0]), src, dst, n);
        }
    }

    /******************************************************************************
       HARD MIXING
    *******************************************************************************/

    if (HardMixing){
        if (pInstance->Target == 0)
            LoadConst_16(0, dst, n);
        else if(pInstance->Shift!=0){
            Shift_Sat_v16xv16 ((LVM_INT16)pInstance->Shift,src,dst,n);
            if ((pInstance->Target>>16) != 0x7FFF)
                Mult3s_16x16( dst, (LVM_INT16)(pInstance->Target>>16), dst, n );
        }
        else {
            if ((pInstance->Target>>16) != 0x7FFF)
                Mult3s_16x16( src, (LVM_INT16)(pInstance->Target>>16), dst, n );
            else if(src!=dst)
                Copy_16(src, dst, n);
        }

    }

    /******************************************************************************
       CALL BACK
    *******************************************************************************/

    if (ptrInstance->MixerStream[0].CallbackSet){
        if (Abs_32(pInstance->Current-pInstance->Target) < pInstance->Delta){
            pInstance->Current = pInstance->Target; /* Difference is not significant anymore.  Make them equal. */
            TargetGain=pInstance->Target>>(16-pInstance->Shift);  // TargetGain in Q16.15 format
            LVC_Mixer_SetTarget(ptrInstance->MixerStream,TargetGain);
            ptrInstance->MixerStream[0].CallbackSet = FALSE;
            if (ptrInstance->MixerStream[0].pCallBack != 0){
                (*ptrInstance->MixerStream[0].pCallBack) ( ptrInstance->MixerStream[0].pCallbackHandle, ptrInstance->MixerStream[0].pGeneralPurpose,ptrInstance->MixerStream[0].CallbackParam );
            }
        }
    }
}

/**********************************************************************************/
