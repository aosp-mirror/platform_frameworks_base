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

#include    "LVPSA.h"
#include    "LVPSA_Private.h"
#include    "LVM_Macros.h"
#include    "VectorArithmetic.h"

#define LVM_MININT_32   0x80000000


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_Process                                               */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  The process applies band pass filters to the signal. Each output                */
/*  feeds a quasi peak filter for level detection.                                  */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance           Pointer to the instance                                     */
/*  pLVPSA_InputSamples Pointer to the input samples buffer                         */
/*  InputBlockSize      Number of mono samples to process                           */
/*  AudioTime           Playback time of the input samples                          */
/*                                                                                  */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVPSA_OK            Succeeds                                                    */
/*  otherwise           Error due to bad parameters                                 */
/*                                                                                  */
/************************************************************************************/
LVPSA_RETURN LVPSA_Process           ( pLVPSA_Handle_t      hInstance,
                                       LVM_INT16           *pLVPSA_InputSamples,
                                       LVM_UINT16           InputBlockSize,
                                       LVPSA_Time           AudioTime            )

{
    LVPSA_InstancePr_t     *pLVPSA_Inst = (LVPSA_InstancePr_t*)hInstance;
    LVM_INT16               *pScratch;
    LVM_INT16               ii;
    LVM_INT32               AudioTimeInc;
    extern LVM_UINT32       LVPSA_SampleRateInvTab[];
    LVM_UINT8               *pWrite_Save;         /* Position of the write pointer at the beginning of the process  */

    /******************************************************************************
       CHECK PARAMETERS
    *******************************************************************************/
    if(hInstance == LVM_NULL || pLVPSA_InputSamples == LVM_NULL)
    {
        return(LVPSA_ERROR_NULLADDRESS);
    }
    if(InputBlockSize == 0 || InputBlockSize > pLVPSA_Inst->MaxInputBlockSize)
    {
        return(LVPSA_ERROR_INVALIDPARAM);
    }

    pScratch = (LVM_INT16*)pLVPSA_Inst->MemoryTable.Region[LVPSA_MEMREGION_SCRATCH].pBaseAddress;
    pWrite_Save = pLVPSA_Inst->pSpectralDataBufferWritePointer;

    /******************************************************************************
       APPLY NEW SETTINGS IF NEEDED
    *******************************************************************************/
    if (pLVPSA_Inst->bControlPending == LVM_TRUE)
    {
        pLVPSA_Inst->bControlPending = 0;
        LVPSA_ApplyNewSettings( pLVPSA_Inst);
    }

    /******************************************************************************
       PROCESS SAMPLES
    *******************************************************************************/
    /* Put samples in range [-0.5;0.5[ for BP filters (see Biquads documentation) */
    Copy_16( pLVPSA_InputSamples,pScratch,(LVM_INT16)InputBlockSize);
    Shift_Sat_v16xv16(-1,pScratch,pScratch,(LVM_INT16)InputBlockSize);

    for (ii = 0; ii < pLVPSA_Inst->nRelevantFilters; ii++)
    {
        switch(pLVPSA_Inst->pBPFiltersPrecision[ii])
        {
            case LVPSA_SimplePrecisionFilter:
                BP_1I_D16F16C14_TRC_WRA_01  ( &pLVPSA_Inst->pBP_Instances[ii],
                                              pScratch,
                                              pScratch + InputBlockSize,
                                              (LVM_INT16)InputBlockSize);
                break;

            case LVPSA_DoublePrecisionFilter:
                BP_1I_D16F32C30_TRC_WRA_01  ( &pLVPSA_Inst->pBP_Instances[ii],
                                              pScratch,
                                              pScratch + InputBlockSize,
                                              (LVM_INT16)InputBlockSize);
                break;
            default:
                break;
        }


        LVPSA_QPD_Process   ( pLVPSA_Inst,
                              pScratch + InputBlockSize,
                              (LVM_INT16)InputBlockSize,
                              ii);
    }

    /******************************************************************************
       UPDATE SpectralDataBufferAudioTime
    *******************************************************************************/

    if(pLVPSA_Inst->pSpectralDataBufferWritePointer != pWrite_Save)
    {
        MUL32x32INTO32((AudioTime + (LVM_INT32)((LVM_INT32)pLVPSA_Inst->LocalSamplesCount*1000)),
                        (LVM_INT32)LVPSA_SampleRateInvTab[pLVPSA_Inst->CurrentParams.Fs],
                        AudioTimeInc,
                        LVPSA_FsInvertShift)
        pLVPSA_Inst->SpectralDataBufferAudioTime = AudioTime + AudioTimeInc;
    }

    return(LVPSA_OK);
}


/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_GetSpectrum                                           */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Gets the levels values at a certain point in time                               */
/*                                                                                  */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  hInstance            Pointer to the instance                                    */
/*  GetSpectrumAudioTime Retrieve the values at this time                           */
/*  pCurrentValues       Pointer to a buffer that will contain levels' values       */
/*  pMaxValues           Pointer to a buffer that will contain max levels' values   */
/*                                                                                  */
/*                                                                                  */
/* RETURNS:                                                                         */
/*  LVPSA_OK            Succeeds                                                    */
/*  otherwise           Error due to bad parameters                                 */
/*                                                                                  */
/************************************************************************************/
LVPSA_RETURN LVPSA_GetSpectrum       ( pLVPSA_Handle_t      hInstance,
                                       LVPSA_Time           GetSpectrumAudioTime,
                                       LVM_UINT8           *pCurrentValues,
                                       LVM_UINT8           *pPeakValues           )

{

    LVPSA_InstancePr_t      *pLVPSA_Inst = (LVPSA_InstancePr_t*)hInstance;
    LVM_INT32               StatusDelta, ii;
    LVM_UINT8               *pRead;

    if(hInstance == LVM_NULL || pCurrentValues == LVM_NULL || pPeakValues == LVM_NULL)
    {
        return(LVPSA_ERROR_NULLADDRESS);
    }


    /* First find the place where to look in the status buffer */
    if(GetSpectrumAudioTime <= pLVPSA_Inst->SpectralDataBufferAudioTime)
    {
        MUL32x32INTO32((pLVPSA_Inst->SpectralDataBufferAudioTime - GetSpectrumAudioTime),LVPSA_InternalRefreshTimeInv,StatusDelta,LVPSA_InternalRefreshTimeShift);
        if((StatusDelta * LVPSA_InternalRefreshTime) != (pLVPSA_Inst->SpectralDataBufferAudioTime - GetSpectrumAudioTime))
        {
            StatusDelta += 1;
        }
    }
    else
    {
        /* This part handles the wrap around */
        MUL32x32INTO32(((pLVPSA_Inst->SpectralDataBufferAudioTime - (LVM_INT32)LVM_MININT_32) + ((LVM_INT32)LVM_MAXINT_32 - GetSpectrumAudioTime)),LVPSA_InternalRefreshTimeInv,StatusDelta,LVPSA_InternalRefreshTimeShift)
        if(((LVM_INT32)(StatusDelta * LVPSA_InternalRefreshTime)) != ((LVM_INT32)((pLVPSA_Inst->SpectralDataBufferAudioTime - (LVM_INT32)LVM_MININT_32) + ((LVM_INT32)LVM_MAXINT_32 - GetSpectrumAudioTime))))
        {
            StatusDelta += 1;
        }
    }
    /* Check whether the desired level is not too "old" (see 2.10 in LVPSA_DesignNotes.doc)*/
    if(
        ((GetSpectrumAudioTime < pLVPSA_Inst->SpectralDataBufferAudioTime)&&
         ((GetSpectrumAudioTime<0)&&(pLVPSA_Inst->SpectralDataBufferAudioTime>0))&&
         (((LVM_INT32)(-GetSpectrumAudioTime + pLVPSA_Inst->SpectralDataBufferAudioTime))>LVM_MAXINT_32))||

         ((GetSpectrumAudioTime > pLVPSA_Inst->SpectralDataBufferAudioTime)&&
         (((GetSpectrumAudioTime>=0)&&(pLVPSA_Inst->SpectralDataBufferAudioTime>=0))||
          ((GetSpectrumAudioTime<=0)&&(pLVPSA_Inst->SpectralDataBufferAudioTime<=0))||
         (((GetSpectrumAudioTime>=0)&&(pLVPSA_Inst->SpectralDataBufferAudioTime<=0))&&
         (((LVM_INT32)(GetSpectrumAudioTime - pLVPSA_Inst->SpectralDataBufferAudioTime))<LVM_MAXINT_32))))||

        (StatusDelta > (LVM_INT32)pLVPSA_Inst->SpectralDataBufferLength) ||
        (!StatusDelta))
    {
        for(ii = 0; ii < pLVPSA_Inst->nBands; ii++)
        {
            pCurrentValues[ii]  = 0;
            pPeakValues[ii]      = 0;
        }
        return(LVPSA_OK);
    }
    /* Set the reading pointer */
    if((LVM_INT32)(StatusDelta * pLVPSA_Inst->nBands) > (pLVPSA_Inst->pSpectralDataBufferWritePointer - pLVPSA_Inst->pSpectralDataBufferStart))
    {
        pRead = pLVPSA_Inst->pSpectralDataBufferWritePointer + (pLVPSA_Inst->SpectralDataBufferLength - (LVM_UINT32)StatusDelta) * pLVPSA_Inst->nBands;
    }
    else
    {
        pRead = pLVPSA_Inst->pSpectralDataBufferWritePointer  - StatusDelta * pLVPSA_Inst->nBands;
    }


    /* Read the status buffer and fill the output buffers */
    for(ii = 0; ii < pLVPSA_Inst->nBands; ii++)
    {
        pCurrentValues[ii] = pRead[ii];
        if(pLVPSA_Inst->pPreviousPeaks[ii] <= pRead[ii])
        {
            pLVPSA_Inst->pPreviousPeaks[ii] = pRead[ii];
        }
        else if(pLVPSA_Inst->pPreviousPeaks[ii] != 0)
        {
            LVM_INT32 temp;
            /*Re-compute max values for decay */
            temp = (LVM_INT32)(LVPSA_MAXUNSIGNEDCHAR - pLVPSA_Inst->pPreviousPeaks[ii]);
            temp = ((temp * LVPSA_MAXLEVELDECAYFACTOR)>>LVPSA_MAXLEVELDECAYSHIFT);
            /* If the gain has no effect, "help" the value to increase */
            if(temp == (LVPSA_MAXUNSIGNEDCHAR - pLVPSA_Inst->pPreviousPeaks[ii]))
            {
                temp += 1;
            }
            /* Saturate */
            temp = (temp > LVPSA_MAXUNSIGNEDCHAR) ? LVPSA_MAXUNSIGNEDCHAR : temp;
            /* Store new max level */
            pLVPSA_Inst->pPreviousPeaks[ii] =  (LVM_UINT8)(LVPSA_MAXUNSIGNEDCHAR - temp);
        }

        pPeakValues[ii] = pLVPSA_Inst->pPreviousPeaks[ii];
    }

    return(LVPSA_OK);
}
