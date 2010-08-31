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
#include "VectorArithmetic.h"

/**********************************************************************************
   DEFINITIONS
***********************************************************************************/

#define TRUE          1
#define FALSE         0



/**********************************************************************************
   FUNCTION MIXSOFT_1ST_D32C31_WRA
***********************************************************************************/

void MixSoft_1St_D32C31_WRA(    Mix_1St_Cll_t       *pInstance,
                                const LVM_INT32     *src,
                                      LVM_INT32     *dst,
                                      LVM_INT16     n)
{
    char HardMixing = TRUE;

    if(n<=0)    return;

    /******************************************************************************
       SOFT MIXING
    *******************************************************************************/
    if (pInstance->Current != pInstance->Target)
    {
        if(pInstance->Alpha == 0){
            pInstance->Current = pInstance->Target;
        }else if ((pInstance->Current-pInstance->Target <POINT_ZERO_ONE_DB)&&
                 (pInstance->Current-pInstance->Target > -POINT_ZERO_ONE_DB)){
            pInstance->Current = pInstance->Target; /* Difference is not significant anymore.  Make them equal. */
        }else{
            /* Soft mixing has to be applied */
            HardMixing = FALSE;
            Core_MixSoft_1St_D32C31_WRA( pInstance, src, dst, n);
        }
    }

    /******************************************************************************
       HARD MIXING
    *******************************************************************************/

    if (HardMixing){
        if (pInstance->Target == 0)
            LoadConst_32(0, dst, n);
        else if ((pInstance->Target>>16) == 0x7FFF){
            if (src != dst)
                Copy_16((LVM_INT16*)src, (LVM_INT16*)dst, (LVM_INT16)(n * 2));
        }
        else
            Mult3s_32x16( src, (LVM_INT16)(pInstance->Current>>16), dst, n );
    }

    /******************************************************************************
       CALL BACK
    *******************************************************************************/

    if (pInstance->CallbackSet){
        if ((pInstance->Current-pInstance->Target <POINT_ZERO_ONE_DB)&&
            (pInstance->Current-pInstance->Target > -POINT_ZERO_ONE_DB)){
            pInstance->Current = pInstance->Target; /* Difference is not significant anymore.  Make them equal. */
            pInstance->CallbackSet = FALSE;
            if (pInstance->pCallBack != 0){
                (*pInstance->pCallBack) ( pInstance->pCallbackHandle, pInstance->pGeneralPurpose,pInstance->CallbackParam );
            }
        }
    }
}

/**********************************************************************************/
