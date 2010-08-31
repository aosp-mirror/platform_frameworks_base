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
   FUNCTION LVC_MixSoft_1St_2i_D16C31_SAT
***********************************************************************************/

void LVC_MixSoft_1St_2i_D16C31_SAT( LVMixer3_2St_st *ptrInstance,
                                  const LVM_INT16             *src,
                                        LVM_INT16             *dst,
                                        LVM_INT16             n)
{
    char        HardMixing = TRUE;
    LVM_INT32   TargetGain;
    Mix_Private_st  *pInstance1=(Mix_Private_st *)(ptrInstance->MixerStream[0].PrivateParams);
    Mix_Private_st  *pInstance2=(Mix_Private_st *)(ptrInstance->MixerStream[1].PrivateParams);

    if(n<=0)    return;

    /******************************************************************************
       SOFT MIXING
    *******************************************************************************/
    if ((pInstance1->Current != pInstance1->Target)||(pInstance2->Current != pInstance2->Target))
    {
        if(pInstance1->Delta == 0x7FFFFFFF)
        {
            pInstance1->Current = pInstance1->Target;
            TargetGain=pInstance1->Target>>16;  // TargetGain in Q16.15 format, no integer part
            LVC_Mixer_SetTarget(&(ptrInstance->MixerStream[0]),TargetGain);
        }
        else if (Abs_32(pInstance1->Current-pInstance1->Target) < pInstance1->Delta)
        {
            pInstance1->Current = pInstance1->Target; /* Difference is not significant anymore.  Make them equal. */
            TargetGain=pInstance1->Target>>16;  // TargetGain in Q16.15 format, no integer part
            LVC_Mixer_SetTarget(&(ptrInstance->MixerStream[0]),TargetGain);
        }
        else
        {
            /* Soft mixing has to be applied */
            HardMixing = FALSE;
        }

        if(HardMixing == TRUE)
        {
            if(pInstance2->Delta == 0x7FFFFFFF)
            {
                pInstance2->Current = pInstance2->Target;
                TargetGain=pInstance2->Target>>16;  // TargetGain in Q16.15 format, no integer part
                LVC_Mixer_SetTarget(&(ptrInstance->MixerStream[1]),TargetGain);
            }
            else if (Abs_32(pInstance2->Current-pInstance2->Target) < pInstance2->Delta)
            {
                pInstance2->Current = pInstance2->Target; /* Difference is not significant anymore.  Make them equal. */
                TargetGain=pInstance2->Target>>16;  // TargetGain in Q16.15 format, no integer part
                LVC_Mixer_SetTarget(&(ptrInstance->MixerStream[1]),TargetGain);
            }
            else
            {
                /* Soft mixing has to be applied */
                HardMixing = FALSE;
            }
        }

        if(HardMixing == FALSE)
        {
             LVC_Core_MixSoft_1St_2i_D16C31_WRA( &(ptrInstance->MixerStream[0]),&(ptrInstance->MixerStream[1]), src, dst, n);
        }
    }

    /******************************************************************************
       HARD MIXING
    *******************************************************************************/

    if (HardMixing)
    {
        if (((pInstance1->Target>>16) == 0x7FFF)&&((pInstance2->Target>>16) == 0x7FFF))
        {
            if(src!=dst)
            {
                Copy_16(src, dst, n);
            }
        }
        else
        {
            LVC_Core_MixHard_1St_2i_D16C31_SAT(&(ptrInstance->MixerStream[0]),&(ptrInstance->MixerStream[1]), src, dst, n);
        }
    }

    /******************************************************************************
       CALL BACK
    *******************************************************************************/

    if (ptrInstance->MixerStream[0].CallbackSet)
    {
        if (Abs_32(pInstance1->Current-pInstance1->Target) < pInstance1->Delta)
        {
            pInstance1->Current = pInstance1->Target; /* Difference is not significant anymore.  Make them equal. */
            TargetGain=pInstance1->Target>>(16-pInstance1->Shift);  // TargetGain in Q16.15 format
            LVC_Mixer_SetTarget(&ptrInstance->MixerStream[0],TargetGain);
            ptrInstance->MixerStream[0].CallbackSet = FALSE;
            if (ptrInstance->MixerStream[0].pCallBack != 0)
            {
                (*ptrInstance->MixerStream[0].pCallBack) ( ptrInstance->MixerStream[0].pCallbackHandle, ptrInstance->MixerStream[0].pGeneralPurpose,ptrInstance->MixerStream[0].CallbackParam );
            }
        }
    }
    if (ptrInstance->MixerStream[1].CallbackSet)
    {
        if (Abs_32(pInstance2->Current-pInstance2->Target) < pInstance2->Delta)
        {
            pInstance2->Current = pInstance2->Target; /* Difference is not significant anymore.  Make them equal. */
            TargetGain=pInstance2->Target>>(16-pInstance2->Shift);  // TargetGain in Q16.15 format
            LVC_Mixer_SetTarget(&ptrInstance->MixerStream[1],TargetGain);
            ptrInstance->MixerStream[1].CallbackSet = FALSE;
            if (ptrInstance->MixerStream[1].pCallBack != 0)
            {
                (*ptrInstance->MixerStream[1].pCallBack) ( ptrInstance->MixerStream[1].pCallbackHandle, ptrInstance->MixerStream[1].pGeneralPurpose,ptrInstance->MixerStream[1].CallbackParam );
            }
        }
    }
}

/**********************************************************************************/
