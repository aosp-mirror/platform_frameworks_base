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
#include "ScalarArithmetic.h"
#include "LVM_Macros.h"

/**********************************************************************************
   FUNCTION LVC_Core_MixSoft_1St_2i_D16C31_WRA
***********************************************************************************/

void LVC_Core_MixSoft_1St_2i_D16C31_WRA( LVMixer3_st        *ptrInstance1,
                                         LVMixer3_st        *ptrInstance2,
                                         const LVM_INT16    *src,
                                         LVM_INT16          *dst,
                                         LVM_INT16          n)
{
    LVM_INT16   OutLoop;
    LVM_INT16   InLoop;
    LVM_INT16   CurrentShortL;
    LVM_INT16   CurrentShortR;
    LVM_INT32   ii;
    Mix_Private_st  *pInstanceL=(Mix_Private_st *)(ptrInstance1->PrivateParams);
    Mix_Private_st  *pInstanceR=(Mix_Private_st *)(ptrInstance2->PrivateParams);

    LVM_INT32   DeltaL=pInstanceL->Delta;
    LVM_INT32   CurrentL=pInstanceL->Current;
    LVM_INT32   TargetL=pInstanceL->Target;

    LVM_INT32   DeltaR=pInstanceR->Delta;
    LVM_INT32   CurrentR=pInstanceR->Current;
    LVM_INT32   TargetR=pInstanceR->Target;

    LVM_INT32   Temp;

    InLoop = (LVM_INT16)(n >> 2); /* Process per 4 samples */
    OutLoop = (LVM_INT16)(n - (InLoop << 2));

    if (OutLoop)
    {
        if(CurrentL<TargetL)
        {
            ADD2_SAT_32x32(CurrentL,DeltaL,Temp);                                      /* Q31 + Q31 into Q31*/
            CurrentL=Temp;
            if (CurrentL > TargetL)
                CurrentL = TargetL;
        }
        else
        {
            CurrentL -= DeltaL;                                                        /* Q31 + Q31 into Q31*/
            if (CurrentL < TargetL)
                CurrentL = TargetL;
        }

        if(CurrentR<TargetR)
        {
            ADD2_SAT_32x32(CurrentR,DeltaR,Temp);                                      /* Q31 + Q31 into Q31*/
            CurrentR=Temp;
            if (CurrentR > TargetR)
                CurrentR = TargetR;
        }
        else
        {
            CurrentR -= DeltaR;                                                        /* Q31 + Q31 into Q31*/
            if (CurrentR < TargetR)
                CurrentR = TargetR;
        }

        CurrentShortL = (LVM_INT16)(CurrentL>>16);                                 /* From Q31 to Q15*/
        CurrentShortR = (LVM_INT16)(CurrentR>>16);                                 /* From Q31 to Q15*/

        for (ii = OutLoop*2; ii != 0; ii-=2)
        {
            *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShortL)>>15);    /* Q15*Q15>>15 into Q15 */
            *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShortR)>>15);    /* Q15*Q15>>15 into Q15 */
        }
    }

    for (ii = InLoop*2; ii != 0; ii-=2)
    {
        if(CurrentL<TargetL)
        {
            ADD2_SAT_32x32(CurrentL,DeltaL,Temp);                                      /* Q31 + Q31 into Q31*/
            CurrentL=Temp;
            if (CurrentL > TargetL)
                CurrentL = TargetL;
        }
        else
        {
            CurrentL -= DeltaL;                                                        /* Q31 + Q31 into Q31*/
            if (CurrentL < TargetL)
                CurrentL = TargetL;
        }

        if(CurrentR<TargetR)
        {
            ADD2_SAT_32x32(CurrentR,DeltaR,Temp);                                      /* Q31 + Q31 into Q31*/
            CurrentR=Temp;
            if (CurrentR > TargetR)
                CurrentR = TargetR;
        }
        else
        {
            CurrentR -= DeltaR;                                                        /* Q31 + Q31 into Q31*/
            if (CurrentR < TargetR)
                CurrentR = TargetR;
        }

        CurrentShortL = (LVM_INT16)(CurrentL>>16);                                 /* From Q31 to Q15*/
        CurrentShortR = (LVM_INT16)(CurrentR>>16);                                 /* From Q31 to Q15*/

        *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShortL)>>15);    /* Q15*Q15>>15 into Q15 */
        *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShortR)>>15);    /* Q15*Q15>>15 into Q15 */
        *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShortL)>>15);
        *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShortR)>>15);
        *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShortL)>>15);
        *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShortR)>>15);
        *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShortL)>>15);
        *(dst++) = (LVM_INT16)(((LVM_INT32)*(src++) * (LVM_INT32)CurrentShortR)>>15);
    }
    pInstanceL->Current=CurrentL;
    pInstanceR->Current=CurrentR;

}
/**********************************************************************************/
