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
#include "BP_1I_D32F32Cll_TRC_WRA_02_Private.h"
#include "LVM_Macros.h"


/**************************************************************************
 ASSUMPTIONS:
 COEFS-
 pBiquadState->coefs[0] is A0,
 pBiquadState->coefs[1] is -B2,
 pBiquadState->coefs[2] is -B1, these are in Q30 format

 DELAYS-
 pBiquadState->pDelays[0] is x(n-1)L in Q0 format
 pBiquadState->pDelays[1] is x(n-2)L in Q0 format
 pBiquadState->pDelays[2] is y(n-1)L in Q0 format
 pBiquadState->pDelays[3] is y(n-2)L in Q0 format
***************************************************************************/

void BP_1I_D32F32C30_TRC_WRA_02 ( Biquad_Instance_t       *pInstance,
                                  LVM_INT32               *pDataIn,
                                  LVM_INT32               *pDataOut,
                                  LVM_INT16               NrSamples)
    {
        LVM_INT32 ynL,templ;
        LVM_INT16 ii;
        PFilter_State pBiquadState = (PFilter_State) pInstance;

        for (ii = NrSamples; ii != 0; ii--)
        {


            /**************************************************************************
                            PROCESSING OF THE LEFT CHANNEL
            ***************************************************************************/
            // ynL= (A0 (Q30) * (x(n)L (Q0) - x(n-2)L (Q0) ) >>30)  in Q0
            templ=(*pDataIn)-pBiquadState->pDelays[1];
            MUL32x32INTO32(pBiquadState->coefs[0],templ,ynL,30)

            // ynL+= ((-B2 (Q30) * y(n-2)L (Q0) ) >>30) in Q0
            MUL32x32INTO32(pBiquadState->coefs[1],pBiquadState->pDelays[3],templ,30)
            ynL+=templ;

            // ynL+= ((-B1 (Q30) * y(n-1)L (Q0) ) >>30) in Q0
            MUL32x32INTO32(pBiquadState->coefs[2],pBiquadState->pDelays[2],templ,30)
            ynL+=templ;

            /**************************************************************************
                            UPDATING THE DELAYS
            ***************************************************************************/
            pBiquadState->pDelays[3]=pBiquadState->pDelays[2]; // y(n-2)L=y(n-1)L
            pBiquadState->pDelays[1]=pBiquadState->pDelays[0]; // x(n-2)L=x(n-1)L
            pBiquadState->pDelays[2]=ynL; // Update y(n-1)L in Q0
            pBiquadState->pDelays[0]=(*pDataIn++); // Update x(n-1)L in Q0

            /**************************************************************************
                            WRITING THE OUTPUT
            ***************************************************************************/
            *pDataOut++=ynL; // Write Left output in Q0

        }

    }

