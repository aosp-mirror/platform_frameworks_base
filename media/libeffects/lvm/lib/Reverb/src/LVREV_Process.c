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

/****************************************************************************************/
/*                                                                                      */
/* Includes                                                                             */
/*                                                                                      */
/****************************************************************************************/
#include "LVREV_Private.h"
#include "VectorArithmetic.h"


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVREV_Process                                               */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Process function for the LVREV module.                                              */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pInData                 Pointer to the input data                                   */
/*  pOutData                Pointer to the output data                                  */
/*  NumSamples              Number of samples in the input buffer                       */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVREV_Success           Succeeded                                                   */
/*  LVREV_INVALIDNUMSAMPLES NumSamples was larger than the maximum block size           */
/*  LVREV_NULLADDRESS       When one of hInstance, pInData or pOutData is NULL          */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. The input and output buffers must be 32-bit aligned                              */
/*                                                                                      */
/****************************************************************************************/
LVREV_ReturnStatus_en LVREV_Process(LVREV_Handle_t      hInstance,
                                    const LVM_INT32     *pInData,
                                    LVM_INT32           *pOutData,
                                    const LVM_UINT16    NumSamples)
{
   LVREV_Instance_st     *pLVREV_Private = (LVREV_Instance_st *)hInstance;
   LVM_INT32             *pInput  = (LVM_INT32 *)pInData;
   LVM_INT32             *pOutput = pOutData;
   LVM_INT32             SamplesToProcess, RemainingSamples;
   LVM_INT32             format = 1;

    /*
     * Check for error conditions
     */

    /* Check for NULL pointers */
    if((hInstance == LVM_NULL) || (pInData == LVM_NULL) || (pOutData == LVM_NULL))
    {
        return LVREV_NULLADDRESS;
    }

    /*
     * Apply the new controls settings if required
     */
    if(pLVREV_Private->bControlPending == LVM_TRUE)
    {
        LVREV_ReturnStatus_en   errorCode;

        /*
         * Clear the pending flag and update the control settings
         */
        pLVREV_Private->bControlPending = LVM_FALSE;

        errorCode = LVREV_ApplyNewSettings (pLVREV_Private);

        if(errorCode != LVREV_SUCCESS)
        {
            return errorCode;
        }
    }

    /*
     * Trap the case where the number of samples is zero.
     */
    if (NumSamples == 0)
    {
        return LVREV_SUCCESS;
    }

    /*
     * If OFF copy and reformat the data as necessary
     */
    if (pLVREV_Private->CurrentParams.OperatingMode == LVM_MODE_OFF)
    {
        if(pInput != pOutput)
        {
            /*
             * Copy the data to the output buffer, convert to stereo is required
             */

            if(pLVREV_Private->CurrentParams.SourceFormat == LVM_MONO){
                MonoTo2I_32(pInput, pOutput, NumSamples);
            } else {
                Copy_16((LVM_INT16 *)pInput,
                        (LVM_INT16 *)pOutput,
                        (LVM_INT16)(NumSamples << 2)); // 32 bit data, stereo
            }
        }

        return LVREV_SUCCESS;
    }

    RemainingSamples = (LVM_INT32)NumSamples;

    if (pLVREV_Private->CurrentParams.SourceFormat != LVM_MONO)
    {
        format = 2;
    }

    while (RemainingSamples!=0)
    {
        /*
         * Process the data
         */

        if(RemainingSamples >  pLVREV_Private->MaxBlkLen)
        {
            SamplesToProcess =  pLVREV_Private->MaxBlkLen;
            RemainingSamples = (LVM_INT16)(RemainingSamples - SamplesToProcess);
        }
        else
        {
            SamplesToProcess = RemainingSamples;
            RemainingSamples = 0;
        }

        ReverbBlock(pInput, pOutput, pLVREV_Private, (LVM_UINT16)SamplesToProcess);

        pInput  = (LVM_INT32 *)(pInput +(SamplesToProcess*format));
        pOutput = (LVM_INT32 *)(pOutput+(SamplesToProcess*2));      // Always stereo output
    }

    return LVREV_SUCCESS;
}



/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                ReverbBlock                                                 */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Process function for the LVREV module.                                              */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pInData                 Pointer to the input data                                   */
/*  pOutData                Pointer to the output data                                  */
/*  NumSamples              Number of samples in the input buffer                       */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVREV_Success           Succeeded                                                   */
/*  LVREV_INVALIDNUMSAMPLES NumSamples was larger than the maximum block size           */
/*  LVREV_NULLADDRESS       When one of hInstance, pInData or pOutData is NULL          */
/*                                                                                      */
/* NOTES:                                                                               */
/*  1. The input and output buffers must be 32-bit aligned                              */
/*                                                                                      */
/****************************************************************************************/

void ReverbBlock(LVM_INT32 *pInput, LVM_INT32 *pOutput, LVREV_Instance_st *pPrivate, LVM_UINT16 NumSamples)
{
    LVM_INT16   j, size;
    LVM_INT32   *pDelayLine;
    LVM_INT32   *pDelayLineInput = pPrivate->pScratch;
    LVM_INT32   *pScratch = pPrivate->pScratch;
    LVM_INT32   *pIn;
    LVM_INT32   *pTemp = pPrivate->pInputSave;
    LVM_INT32   NumberOfDelayLines;

    /******************************************************************************
     * All calculations will go into the buffer pointed to by pTemp, this will    *
     * then be mixed with the original input to create the final output.          *
     *                                                                            *
     * When INPLACE processing is selected this must be a temporary buffer and    *
     * hence this is the worst case, so for simplicity this will ALWAYS be so     *
     *                                                                            *
     * The input buffer will remain untouched until the output of the mixer if    *
     * INPLACE processing is selected.                                            *
     *                                                                            *
     * The temp buffer will always be NumSamples in size regardless of MONO or    *
     * STEREO input. In the case of stereo input all processing is done in MONO   *
     * and the final output is converted to STEREO after the mixer                *
     ******************************************************************************/

    if(pPrivate->InstanceParams.NumDelays == LVREV_DELAYLINES_4 )
    {
        NumberOfDelayLines = 4;
    }
    else if(pPrivate->InstanceParams.NumDelays == LVREV_DELAYLINES_2 )
    {
        NumberOfDelayLines = 2;
    }
    else
    {
        NumberOfDelayLines = 1;
    }

    if(pPrivate->CurrentParams.SourceFormat == LVM_MONO)
    {
        pIn = pInput;
    }
    else
    {
        /*
         *  Stereo to mono conversion
         */

        From2iToMono_32( pInput,
                         pTemp,
                         (LVM_INT16)NumSamples);

        pIn = pTemp;
    }

    Mult3s_32x16(pIn,
                 (LVM_INT16)LVREV_HEADROOM,
                 pTemp,
                 (LVM_INT16)NumSamples);

    /*
     *  High pass filter
     */
    FO_1I_D32F32C31_TRC_WRA_01( &pPrivate->pFastCoef->HPCoefs,
                                pTemp,
                                pTemp,
                                (LVM_INT16)NumSamples);
    /*
     *  Low pass filter
     */
    FO_1I_D32F32C31_TRC_WRA_01( &pPrivate->pFastCoef->LPCoefs,
                                pTemp,
                                pTemp,
                                (LVM_INT16)NumSamples);

    /*
     *  Process all delay lines
     */

    for(j = 0; j < NumberOfDelayLines; j++)
    {
        pDelayLine = pPrivate->pScratchDelayLine[j];

        /*
         * All-pass filter with pop and click suppression
         */
        /* Get the smoothed, delayed output. Put it in the output buffer */
        MixSoft_2St_D32C31_SAT(&pPrivate->Mixer_APTaps[j],
                               pPrivate->pOffsetA[j],
                               pPrivate->pOffsetB[j],
                               pDelayLine,
                               (LVM_INT16)NumSamples);
        /* Re-align the all pass filter delay buffer and copying the fixed delay data to the AP delay in the process */
        Copy_16((LVM_INT16 *)&pPrivate->pDelay_T[j][NumSamples],
                (LVM_INT16 *)pPrivate->pDelay_T[j],
                (LVM_INT16)((pPrivate->T[j]-NumSamples) << 1));         /* 32-bit data */
        /* Apply the smoothed feedback and save to fixed delay input (currently empty) */
        MixSoft_1St_D32C31_WRA(&pPrivate->Mixer_SGFeedback[j],
                               pDelayLine,
                               &pPrivate->pDelay_T[j][pPrivate->T[j]-NumSamples],
                               (LVM_INT16)NumSamples);
        /* Sum into the AP delay line */
        Mac3s_Sat_32x16(&pPrivate->pDelay_T[j][pPrivate->T[j]-NumSamples],
                        -0x7fff,                                        /* Invert since the feedback coefficient is negative */
                        &pPrivate->pDelay_T[j][pPrivate->Delay_AP[j]-NumSamples],
                        (LVM_INT16)NumSamples);
        /* Apply smoothed feedforward sand save to fixed delay input (currently empty) */
        MixSoft_1St_D32C31_WRA(&pPrivate->Mixer_SGFeedforward[j],
                               &pPrivate->pDelay_T[j][pPrivate->Delay_AP[j]-NumSamples],
                               &pPrivate->pDelay_T[j][pPrivate->T[j]-NumSamples],
                               (LVM_INT16)NumSamples);
        /* Sum into the AP output */
        Mac3s_Sat_32x16(&pPrivate->pDelay_T[j][pPrivate->T[j]-NumSamples],
                        0x7fff,
                        pDelayLine,
                        (LVM_INT16)NumSamples);

        /*
         *  Feedback gain
         */
        MixSoft_1St_D32C31_WRA(&pPrivate->FeedbackMixer[j], pDelayLine, pDelayLine, NumSamples);

        /*
         *  Low pass filter
         */
        FO_1I_D32F32C31_TRC_WRA_01( &pPrivate->pFastCoef->RevLPCoefs[j],
                                    pDelayLine,
                                    pDelayLine,
                                    (LVM_INT16)NumSamples);
    }

    /*
     *  Apply rotation matrix and delay samples
     */
    for(j = 0; j < NumberOfDelayLines; j++)
    {

        Copy_16( (LVM_INT16*)(pTemp),
                 (LVM_INT16*)(pDelayLineInput),
                 (LVM_INT16)(NumSamples << 1));

        /*
         *  Rotation matrix mix
         */
        switch(j)
        {
            case 3:
                /*
                 *  Add delay line 1 and 2 contribution
                 */
                 Mac3s_Sat_32x16(pPrivate->pScratchDelayLine[1], -0x8000, pDelayLineInput, (LVM_INT16)NumSamples);
                 Mac3s_Sat_32x16(pPrivate->pScratchDelayLine[2], -0x8000, pDelayLineInput, (LVM_INT16)NumSamples);

                break;
            case 2:

                /*
                 *  Add delay line 0 and 3 contribution
                 */
                 Mac3s_Sat_32x16(pPrivate->pScratchDelayLine[0], -0x8000, pDelayLineInput, (LVM_INT16)NumSamples);
                 Mac3s_Sat_32x16(pPrivate->pScratchDelayLine[3], -0x8000, pDelayLineInput, (LVM_INT16)NumSamples);

                break;
            case 1:
                if(pPrivate->InstanceParams.NumDelays == LVREV_DELAYLINES_4)
                {
                    /*
                     *  Add delay line 0 and 3 contribution
                     */
                    Mac3s_Sat_32x16(pPrivate->pScratchDelayLine[0], -0x8000, pDelayLineInput, (LVM_INT16)NumSamples);
                    Add2_Sat_32x32(pPrivate->pScratchDelayLine[3], pDelayLineInput, (LVM_INT16)NumSamples);

                }
                else
                {
                    /*
                     *  Add delay line 0 and 1 contribution
                     */
                     Mac3s_Sat_32x16(pPrivate->pScratchDelayLine[0], -0x8000, pDelayLineInput, (LVM_INT16)NumSamples);
                     Mac3s_Sat_32x16(pPrivate->pScratchDelayLine[1], -0x8000, pDelayLineInput, (LVM_INT16)NumSamples);

                }
                break;
            case 0:
                if(pPrivate->InstanceParams.NumDelays == LVREV_DELAYLINES_4)
                {
                    /*
                     *  Add delay line 1 and 2 contribution
                     */
                    Mac3s_Sat_32x16(pPrivate->pScratchDelayLine[1], -0x8000, pDelayLineInput, (LVM_INT16)NumSamples);
                    Add2_Sat_32x32(pPrivate->pScratchDelayLine[2], pDelayLineInput, (LVM_INT16)NumSamples);

                }
                else if(pPrivate->InstanceParams.NumDelays == LVREV_DELAYLINES_2)
                {
                    /*
                     *  Add delay line 0 and 1 contribution
                     */
                    Add2_Sat_32x32(pPrivate->pScratchDelayLine[0], pDelayLineInput, (LVM_INT16)NumSamples);
                    Mac3s_Sat_32x16(pPrivate->pScratchDelayLine[1], -0x8000, pDelayLineInput, (LVM_INT16)NumSamples);

                }
                else
                {
                    /*
                     *  Add delay line 0 contribution
                     */

                    /*             SOURCE                          DESTINATION*/
                    Add2_Sat_32x32(pPrivate->pScratchDelayLine[0], pDelayLineInput, (LVM_INT16)NumSamples);
                }
                break;
            default:
                break;
        }

        /*
         *  Delay samples
         */
        Copy_16((LVM_INT16 *)pDelayLineInput,
                (LVM_INT16 *)&pPrivate->pDelay_T[j][pPrivate->T[j]-NumSamples],
                (LVM_INT16)(NumSamples << 1));              /* 32-bit data */

    }


    /*
     *  Create stereo output
     */
    switch(pPrivate->InstanceParams.NumDelays)
    {
        case LVREV_DELAYLINES_4:
             Add2_Sat_32x32(pPrivate->pScratchDelayLine[3],
                            pPrivate->pScratchDelayLine[0],
                            (LVM_INT16)NumSamples);
             Add2_Sat_32x32(pPrivate->pScratchDelayLine[2],
                            pPrivate->pScratchDelayLine[1],
                            (LVM_INT16)NumSamples);


            JoinTo2i_32x32(pPrivate->pScratchDelayLine[0],
                           pPrivate->pScratchDelayLine[1],
                           pTemp,
                           (LVM_INT16)NumSamples);


            break;
        case LVREV_DELAYLINES_2:

             Copy_16( (LVM_INT16*)pPrivate->pScratchDelayLine[1],
                      (LVM_INT16*)pScratch,
                      (LVM_INT16)(NumSamples << 1));

            Mac3s_Sat_32x16(pPrivate->pScratchDelayLine[0],
                            -0x8000,
                            pScratch,
                            (LVM_INT16)NumSamples);

             Add2_Sat_32x32(pPrivate->pScratchDelayLine[1],
                            pPrivate->pScratchDelayLine[0],
                            (LVM_INT16)NumSamples);


             JoinTo2i_32x32(pPrivate->pScratchDelayLine[0],
                            pScratch,
                            pTemp,
                            (LVM_INT16)NumSamples);
            break;
        case LVREV_DELAYLINES_1:
            MonoTo2I_32(pPrivate->pScratchDelayLine[0],
                        pTemp,
                        (LVM_INT16)NumSamples);
            break;
        default:
            break;
    }


    /*
     *  Dry/wet mixer
     */

    size = (LVM_INT16)(NumSamples << 1);
    MixSoft_2St_D32C31_SAT(&pPrivate->BypassMixer,
                           pTemp,
                           pTemp,
                           pOutput,
                           size);

    /* Apply Gain*/

    Shift_Sat_v32xv32 (LVREV_OUTPUTGAIN_SHIFT,
                       pOutput,
                       pOutput,
                       size);

    MixSoft_1St_D32C31_WRA(&pPrivate->GainMixer,
                           pOutput,
                           pOutput,
                           size);

    return;
}


/* End of file */

