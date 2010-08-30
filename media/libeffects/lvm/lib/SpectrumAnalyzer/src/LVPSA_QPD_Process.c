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

#include "LVPSA_QPD.h"
#include "LVPSA_Private.h"

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_QPD_WritePeak                                         */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Write a level value in the buffer in the corresponding band.                    */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInst               Pointer to the LVPSA instance                               */
/*  ppWrite             Pointer to pointer to the buffer                            */
/*  CallNumber          Number of the band the value should be written in           */
/*  Value               Value to write in the buffer                                */
/*                                                                                  */
/* RETURNS:             void                                                        */
/*                                                                                  */
/************************************************************************************/
void LVPSA_QPD_WritePeak(   pLVPSA_InstancePr_t       pLVPSA_Inst,
                            LVM_UINT8             **ppWrite,
                            LVM_INT16               BandIndex,
                            LVM_INT16               Value   );



/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_QPD_Process                                           */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Apply downsampling, post gain, quasi peak filtering and write the levels values */
/*  in the buffer every 20 ms.                                                      */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*                                                                                  */
/* RETURNS:             void                                                        */
/*                                                                                  */
/************************************************************************************/
void LVPSA_QPD_Process (            void                               *hInstance,
                                    LVM_INT16                          *pInSamps,
                                    LVM_INT16                           numSamples,
                                    LVM_INT16                           BandIndex)
{

    /******************************************************************************
       PARAMETERS
    *******************************************************************************/
    LVPSA_InstancePr_t     *pLVPSA_Inst = (LVPSA_InstancePr_t*)hInstance;
    QPD_State_t *pQPDState =  (QPD_State_t*)&pLVPSA_Inst->pQPD_States[BandIndex];

    /* Pointer to taps */
    LVM_INT32* pDelay  = pQPDState->pDelay;

    /* Parameters needed during quasi peak calculations */
    LVM_INT32   X0;
    LVM_INT32   temp,temp2;
    LVM_INT32   accu;
    LVM_INT16   Xg0;
    LVM_INT16   D0;
    LVM_INT16   V0 = (LVM_INT16)(*pDelay);

    /* Filter's coef */
    LVM_INT32   Kp = pQPDState->Coefs[0];
    LVM_INT32   Km = pQPDState->Coefs[1];

    LVM_INT16   ii = numSamples;

    LVM_UINT8  *pWrite = pLVPSA_Inst->pSpectralDataBufferWritePointer;
    LVM_INT32   BufferUpdateSamplesCount = pLVPSA_Inst->BufferUpdateSamplesCount;
    LVM_UINT16  DownSamplingFactor = pLVPSA_Inst->DownSamplingFactor;

    /******************************************************************************
       INITIALIZATION
    *******************************************************************************/
    /* Correct the pointer to take the first down sampled signal sample */
    pInSamps += pLVPSA_Inst->DownSamplingCount;
    /* Correct also the number of samples */
    ii = (LVM_INT16)(ii - (LVM_INT16)pLVPSA_Inst->DownSamplingCount);

    while (ii > 0)
    {
        /* Apply post gain */
        X0 = ((*pInSamps) * pLVPSA_Inst->pPostGains[BandIndex]) >> (LVPSA_GAINSHIFT-1); /* - 1 to compensate scaling in process function*/
        pInSamps = pInSamps + DownSamplingFactor;

        /* Saturate and take absolute value */
        if(X0 < 0)
            X0 = -X0;
        if (X0 > 0x7FFF)
            Xg0 = 0x7FFF;
        else
            Xg0 = (LVM_INT16)(X0);


        /* Quasi peak filter calculation */
        D0  = (LVM_INT16)(Xg0 - V0);

        temp2 = (LVM_INT32)D0;
        MUL32x32INTO32(temp2,Kp,accu,31);

        D0    = (LVM_INT16)(D0>>1);
        if (D0 < 0){
            D0 = (LVM_INT16)(-D0);
        }

        temp2 = (LVM_INT32)D0;
        MUL32x32INTO32((LVM_INT32)D0,Km,temp,31);
        accu +=temp + Xg0;

        if (accu > 0x7FFF)
            accu = 0x7FFF;
        else if(accu < 0)
            accu = 0x0000;

        V0 = (LVM_INT16)accu;

        if(((pLVPSA_Inst->nSamplesBufferUpdate - BufferUpdateSamplesCount) < DownSamplingFactor))
        {
            LVPSA_QPD_WritePeak( pLVPSA_Inst,
                                &pWrite,
                                 BandIndex,
                                 V0);
            BufferUpdateSamplesCount -= pLVPSA_Inst->nSamplesBufferUpdate;
            pLVPSA_Inst->LocalSamplesCount = (LVM_UINT16)(numSamples - ii);
        }
        BufferUpdateSamplesCount+=DownSamplingFactor;

        ii = (LVM_INT16)(ii-DownSamplingFactor);

    }

    /* Store last taps in memory */
    *pDelay = (LVM_INT32)(V0);

    /* If this is the last call to the function after last band processing,
       update the parameters. */
    if(BandIndex == (pLVPSA_Inst->nRelevantFilters-1))
    {
        pLVPSA_Inst->pSpectralDataBufferWritePointer = pWrite;
        /* Adjustment for 11025Hz input, 220,5 is normally
           the exact number of samples for 20ms.*/
        if((pLVPSA_Inst->pSpectralDataBufferWritePointer != pWrite)&&(pLVPSA_Inst->CurrentParams.Fs == LVM_FS_11025))
        {
            if(pLVPSA_Inst->nSamplesBufferUpdate == 220)
            {
                pLVPSA_Inst->nSamplesBufferUpdate = 221;
            }
            else
            {
                pLVPSA_Inst->nSamplesBufferUpdate = 220;
            }
        }
        pLVPSA_Inst->pSpectralDataBufferWritePointer = pWrite;
        pLVPSA_Inst->BufferUpdateSamplesCount = BufferUpdateSamplesCount;
        pLVPSA_Inst->DownSamplingCount = (LVM_UINT16)(-ii);
    }
}

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_QPD_WritePeak                                         */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Write a level value in the spectrum data buffer in the corresponding band.      */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pLVPSA_Inst               Pointer to the LVPSA instance                         */
/*  ppWrite             Pointer to pointer to the buffer                            */
/*  CallNumber          Number of the band the value should be written in           */
/*  Value               Value to write in the spectrum data buffer                  */
/*                                                                                  */
/* RETURNS:             void                                                        */
/*                                                                                  */
/************************************************************************************/
void LVPSA_QPD_WritePeak(   pLVPSA_InstancePr_t       pLVPSA_Inst,
                            LVM_UINT8             **ppWrite,
                            LVM_INT16               BandIndex,
                            LVM_INT16               Value   )
{
    LVM_UINT8 *pWrite = *ppWrite;


    /* Write the value and update the write pointer */
    *(pWrite + BandIndex) = (LVM_UINT8)(Value>>7);
    pWrite += pLVPSA_Inst->nBands;
    if (pWrite == (pLVPSA_Inst->pSpectralDataBufferStart + pLVPSA_Inst->nBands * pLVPSA_Inst->SpectralDataBufferLength))
    {
        pWrite = pLVPSA_Inst->pSpectralDataBufferStart;
    }

    *ppWrite = pWrite;

}

