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

#include "BIQUAD.h"
#include "DC_2I_D16_TRC_WRA_01_Private.h"
#include "LVM_Macros.h"

void DC_2I_D16_TRC_WRA_01( Biquad_Instance_t       *pInstance,
                           LVM_INT16               *pDataIn,
                           LVM_INT16               *pDataOut,
                           LVM_INT16               NrSamples)
    {
        LVM_INT32 LeftDC,RightDC;
        LVM_INT32 Diff;
        LVM_INT32 j;
        PFilter_State pBiquadState = (PFilter_State) pInstance;

        LeftDC  =   pBiquadState->LeftDC;
        RightDC =   pBiquadState->RightDC;
        for(j=NrSamples-1;j>=0;j--)
        {
            /* Subtract DC an saturate */
            Diff=*(pDataIn++)-(LeftDC>>16);
            if (Diff > 32767) {
                Diff = 32767; }
            else if (Diff < -32768) {
                Diff = -32768; }
            *(pDataOut++)=(LVM_INT16)Diff;
            if (Diff < 0) {
                LeftDC -= DC_D16_STEP; }
            else {
                LeftDC += DC_D16_STEP; }


            /* Subtract DC an saturate */
            Diff=*(pDataIn++)-(RightDC>>16);
            if (Diff > 32767) {
                Diff = 32767; }
            else if (Diff < -32768) {
                Diff = -32768; }
            *(pDataOut++)=(LVM_INT16)Diff;
            if (Diff < 0) {
                RightDC -= DC_D16_STEP; }
            else {
                RightDC += DC_D16_STEP; }

        }
        pBiquadState->LeftDC    =   LeftDC;
        pBiquadState->RightDC   =   RightDC;


    }

