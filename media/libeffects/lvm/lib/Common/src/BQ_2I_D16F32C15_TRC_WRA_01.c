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
#include "BQ_2I_D16F32Css_TRC_WRA_01_Private.h"
#include "LVM_Macros.h"

/**************************************************************************
 ASSUMPTIONS:
 COEFS-
 pBiquadState->coefs[0] is A2, pBiquadState->coefs[1] is A1
 pBiquadState->coefs[2] is A0, pBiquadState->coefs[3] is -B2
 pBiquadState->coefs[4] is -B1, these are in Q15 format

 DELAYS-
 pBiquadState->pDelays[0] is x(n-1)L in Q0 format
 pBiquadState->pDelays[1] is x(n-1)R in Q0 format
 pBiquadState->pDelays[2] is x(n-2)L in Q0 format
 pBiquadState->pDelays[3] is x(n-2)R in Q0 format
 pBiquadState->pDelays[4] is y(n-1)L in Q16 format
 pBiquadState->pDelays[5] is y(n-1)R in Q16 format
 pBiquadState->pDelays[6] is y(n-2)L in Q16 format
 pBiquadState->pDelays[7] is y(n-2)R in Q16 format
***************************************************************************/

void BQ_2I_D16F32C15_TRC_WRA_01 (           Biquad_Instance_t       *pInstance,
                                            LVM_INT16                    *pDataIn,
                                            LVM_INT16                    *pDataOut,
                                            LVM_INT16                    NrSamples)
    {
        LVM_INT32  ynL,ynR,templ;
        LVM_INT16 ii;
        PFilter_State pBiquadState = (PFilter_State) pInstance;

         for (ii = NrSamples; ii != 0; ii--)
         {


            /**************************************************************************
                            PROCESSING OF THE LEFT CHANNEL
            ***************************************************************************/
            /* ynL=A2 (Q15) * x(n-2)L (Q0) in Q15*/
            ynL=(LVM_INT32)pBiquadState->coefs[0]* pBiquadState->pDelays[2];

            /* ynL+=A1 (Q15) * x(n-1)L (Q0) in Q15*/
            ynL+=(LVM_INT32)pBiquadState->coefs[1]* pBiquadState->pDelays[0];

            /* ynL+=A0 (Q15) * x(n)L (Q0) in Q15*/
            ynL+=(LVM_INT32)pBiquadState->coefs[2]* (*pDataIn);

            /* ynL+= ( (-B2 (Q15) * y(n-2)L (Q16) )>>16) in Q15 */
            MUL32x16INTO32(pBiquadState->pDelays[6],pBiquadState->coefs[3],templ,16)
            ynL+=templ;

            /* ynL+=( (-B1 (Q15) * y(n-1)L (Q16) )>>16) in Q15 */
            MUL32x16INTO32(pBiquadState->pDelays[4],pBiquadState->coefs[4],templ,16)
            ynL+=templ;

            /**************************************************************************
                            PROCESSING OF THE RIGHT CHANNEL
            ***************************************************************************/
            /* ynR=A2 (Q15) * x(n-2)R (Q0) in Q15*/
            ynR=(LVM_INT32)pBiquadState->coefs[0]*pBiquadState->pDelays[3];

            /* ynR+=A1 (Q15) * x(n-1)R (Q0) in Q15*/
            ynR+=(LVM_INT32)pBiquadState->coefs[1]*pBiquadState->pDelays[1];

            /* ynR+=A0 (Q15) * x(n)R (Q0) in Q15*/
            ynR+=(LVM_INT32)pBiquadState->coefs[2]*(*(pDataIn+1));

            /* ynR+= ( (-B2 (Q15) * y(n-2)R (Q16) )>>16) in Q15 */
            MUL32x16INTO32(pBiquadState->pDelays[7],pBiquadState->coefs[3],templ,16)
            ynR+=templ;

            /* ynR+=( (-B1 (Q15) * y(n-1)R (Q16) )>>16) in Q15 */
            MUL32x16INTO32(pBiquadState->pDelays[5],pBiquadState->coefs[4],templ,16)
            ynR+=templ;

            /**************************************************************************
                            UPDATING THE DELAYS
            ***************************************************************************/
            pBiquadState->pDelays[7]=pBiquadState->pDelays[5]; /* y(n-2)R=y(n-1)R*/
            pBiquadState->pDelays[6]=pBiquadState->pDelays[4]; /* y(n-2)L=y(n-1)L*/
            pBiquadState->pDelays[3]=pBiquadState->pDelays[1]; /* x(n-2)R=x(n-1)R*/
            pBiquadState->pDelays[2]=pBiquadState->pDelays[0]; /* x(n-2)L=x(n-1)L*/
            pBiquadState->pDelays[5]=ynR<<1; /* Update y(n-1)R in Q16*/
            pBiquadState->pDelays[4]=ynL<<1; /* Update y(n-1)L in Q16*/
            pBiquadState->pDelays[0]=(*pDataIn); /* Update x(n-1)L in Q0*/
            pDataIn++;
            pBiquadState->pDelays[1]=(*pDataIn); /* Update x(n-1)R in Q0*/
            pDataIn++;

            /**************************************************************************
                            WRITING THE OUTPUT
            ***************************************************************************/
            *pDataOut=(LVM_INT16)(ynL>>15); /* Write Left output in Q0*/
            pDataOut++;
            *pDataOut=(LVM_INT16)(ynR>>15); /* Write Right ouput in Q0*/
            pDataOut++;
        }

    }

