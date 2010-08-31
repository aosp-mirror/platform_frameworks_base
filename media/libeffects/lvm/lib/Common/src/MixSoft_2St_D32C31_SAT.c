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
   FUNCTION MIXSOFT_2ST_D32C31_SAT
***********************************************************************************/

void MixSoft_2St_D32C31_SAT(    Mix_2St_Cll_t       *pInstance,
                                const LVM_INT32     *src1,
                                const LVM_INT32     *src2,
                                      LVM_INT32     *dst,
                                      LVM_INT16     n)
{

    if(n<=0)    return;

    /******************************************************************************
       SOFT MIXING
    *******************************************************************************/
    if ((pInstance->Current1 != pInstance->Target1) || (pInstance->Current2 != pInstance->Target2))
    {
        MixSoft_1St_D32C31_WRA( (Mix_1St_Cll_t*) pInstance, src1, dst, n);
        MixInSoft_D32C31_SAT( (void *) &pInstance->Alpha2,     /* Cast to void: no dereferencing in function*/
            src2, dst, n);
    }

    /******************************************************************************
       HARD MIXING
    *******************************************************************************/

    else
    {
        if (pInstance->Current1 == 0)
            MixSoft_1St_D32C31_WRA( (void *) &pInstance->Alpha2, /* Cast to void: no dereferencing in function*/
            src2, dst, n);
        else if (pInstance->Current2 == 0)
            MixSoft_1St_D32C31_WRA( (Mix_1St_Cll_t*) pInstance, src1, dst, n);
        else
            Core_MixHard_2St_D32C31_SAT( pInstance, src1, src2, dst, n);
    }
}

/**********************************************************************************/
