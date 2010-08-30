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
/*                                                                                        */
/*    Includes                                                                              */
/*                                                                                        */
/****************************************************************************************/

#include "LVM_Private.h"
#include "VectorArithmetic.h"

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVM_BufferManagedIn                                        */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    Full buffer management allowing the user to provide input and output buffers on   */
/*  any alignment and with any number of samples. The alignment is corrected within     */
/*  the buffer management and the samples are grouped in to blocks of the correct size  */
/*  before processing.                                                                  */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*    hInstance        -    Instance handle                                             */
/*    pInData            -    Pointer to the input data stream                          */
/*  *pToProcess        -    Pointer to pointer to the start of data processing          */
/*  *pProcessed        -    Pointer to pointer to the destination of the processed data */
/*    pNumSamples        -    Pointer to the number of samples to process               */
/*                                                                                      */
/* RETURNS:                                                                             */
/*    None                                                                              */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/

void LVM_BufferManagedIn(LVM_Handle_t       hInstance,
                         const LVM_INT16    *pInData,
                         LVM_INT16          **pToProcess,
                         LVM_INT16          **pProcessed,
                         LVM_UINT16         *pNumSamples)
{

    LVM_INT16        SampleCount;           /* Number of samples to be processed this call */
    LVM_INT16        NumSamples;            /* Number of samples in scratch buffer */
    LVM_INT16        *pStart;
    LVM_Instance_t   *pInstance = (LVM_Instance_t  *)hInstance;
    LVM_Buffer_t     *pBuffer;
    LVM_INT16        *pDest;
    LVM_INT16        NumChannels =2;


    /*
     * Set the processing address pointers
     */
    pBuffer     = pInstance->pBufferManagement;
    pDest       = pBuffer->pScratch;
    *pToProcess = pBuffer->pScratch;
    *pProcessed = pBuffer->pScratch;

    /*
     * Check if it is the first call of a block
     */
    if (pInstance->SamplesToProcess == 0)
    {
        /*
         * First call for a new block of samples
         */
        pInstance->SamplesToProcess = (LVM_INT16)(*pNumSamples + pBuffer->InDelaySamples);
        pInstance->pInputSamples    = (LVM_INT16 *)pInData;
        pBuffer->BufferState        = LVM_FIRSTCALL;
    }
    pStart = pInstance->pInputSamples;                       /* Pointer to the input samples */
    pBuffer->SamplesToOutput  = 0;                           /* Samples to output is same as number read for inplace processing */


    /*
     * Calculate the number of samples to process this call and update the buffer state
     */
    if (pInstance->SamplesToProcess > pInstance->InternalBlockSize)
    {
        /*
         * Process the maximum bock size of samples.
         */
        SampleCount = pInstance->InternalBlockSize;
        NumSamples  = pInstance->InternalBlockSize;
    }
    else
    {
        /*
         * Last call for the block, so calculate how many frames and samples to process
          */
        LVM_INT16   NumFrames;

        NumSamples  = pInstance->SamplesToProcess;
        NumFrames    = (LVM_INT16)(NumSamples >> MIN_INTERNAL_BLOCKSHIFT);
        SampleCount = (LVM_INT16)(NumFrames << MIN_INTERNAL_BLOCKSHIFT);

        /*
         * Update the buffer state
         */
        if (pBuffer->BufferState == LVM_FIRSTCALL)
        {
            pBuffer->BufferState = LVM_FIRSTLASTCALL;
        }
        else
        {
            pBuffer->BufferState = LVM_LASTCALL;
        }
    }
    *pNumSamples = (LVM_UINT16)SampleCount;                        /* Set the number of samples to process this call */


    /*
     * Copy samples from the delay buffer as required
     */
    if (((pBuffer->BufferState == LVM_FIRSTCALL) ||
        (pBuffer->BufferState == LVM_FIRSTLASTCALL)) &&
        (pBuffer->InDelaySamples != 0))
    {
        Copy_16(&pBuffer->InDelayBuffer[0],                             /* Source */
                pDest,                                                  /* Destination */
                (LVM_INT16)(NumChannels*pBuffer->InDelaySamples));      /* Number of delay samples, left and right */
        NumSamples = (LVM_INT16)(NumSamples - pBuffer->InDelaySamples); /* Update sample count */
        pDest += NumChannels * pBuffer->InDelaySamples;                 /* Update the destination pointer */
    }


    /*
     * Copy the rest of the samples for this call from the input buffer
     */
    if (NumSamples > 0)
    {
        Copy_16(pStart,                                             /* Source */
                pDest,                                              /* Destination */
                (LVM_INT16)(NumChannels*NumSamples));               /* Number of input samples */
        pStart += NumChannels * NumSamples;                         /* Update the input pointer */

        /*
         * Update the input data pointer and samples to output
         */
        pBuffer->SamplesToOutput = (LVM_INT16)(pBuffer->SamplesToOutput + NumSamples); /* Update samples to output */
    }


    /*
      * Update the sample count and input pointer
     */
    pInstance->SamplesToProcess  = (LVM_INT16)(pInstance->SamplesToProcess - SampleCount);      /* Update the count of samples */
    pInstance->pInputSamples     = pStart;                                                      /* Update input sample pointer */


    /*
     * Save samples to the delay buffer if any left unprocessed
     */
    if ((pBuffer->BufferState == LVM_FIRSTLASTCALL) ||
        (pBuffer->BufferState == LVM_LASTCALL))
    {
        NumSamples = pInstance->SamplesToProcess;
        pStart     = pBuffer->pScratch;                             /* Start of the buffer */
        pStart    += NumChannels*SampleCount;                       /* Offset by the number of processed samples */
        if (NumSamples != 0)
        {
            Copy_16(pStart,                                         /* Source */
                    &pBuffer->InDelayBuffer[0],                     /* Destination */
                    (LVM_INT16)(NumChannels*NumSamples));           /* Number of input samples */
        }


        /*
         * Update the delay sample count
         */
        pBuffer->InDelaySamples     = NumSamples;                   /* Number of delay sample pairs */
        pInstance->SamplesToProcess = 0;                            /* All Samples used */
    }
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVM_BufferUnmanagedIn                                      */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    This mode is selected by the user code and disables the buffer management with the */
/*  exception of the maximum block size processing. The user must ensure that the       */
/*  input and output buffers are 32-bit aligned and also that the number of samples to  */
/*    process is a correct multiple of samples.                                         */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*    hInstance        -    Instance handle                                             */
/*  *pToProcess        -    Pointer to the start of data processing                     */
/*  *pProcessed        -    Pointer to the destination of the processed data            */
/*    pNumSamples        -    Pointer to the number of samples to process               */
/*                                                                                      */
/* RETURNS:                                                                             */
/*    None                                                                              */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/

void LVM_BufferUnmanagedIn(LVM_Handle_t     hInstance,
                           LVM_INT16        **pToProcess,
                           LVM_INT16        **pProcessed,
                           LVM_UINT16       *pNumSamples)
{

    LVM_Instance_t    *pInstance = (LVM_Instance_t  *)hInstance;


    /*
     * Check if this is the first call of a block
     */
    if (pInstance->SamplesToProcess == 0)
    {
        pInstance->SamplesToProcess = (LVM_INT16)*pNumSamples;       /* Get the number of samples on first call */
        pInstance->pInputSamples    = *pToProcess;                   /* Get the I/O pointers */
        pInstance->pOutputSamples    = *pProcessed;


        /*
         * Set te block size to process
         */
        if (pInstance->SamplesToProcess > pInstance->InternalBlockSize)
        {
            *pNumSamples = (LVM_UINT16)pInstance->InternalBlockSize;
        }
        else
        {
            *pNumSamples = (LVM_UINT16)pInstance->SamplesToProcess;
        }
    }

    /*
     * Set the process pointers
     */
    *pToProcess = pInstance->pInputSamples;
    *pProcessed = pInstance->pOutputSamples;
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVM_BufferOptimisedIn                                      */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    Optimised buffer management for the case where the data is outplace processing,   */
/*    the output data is 32-bit aligned and there are sufficient samples to allow some  */
/*    processing directly in the output buffer. This saves one data copy per sample     */
/*    compared with the unoptimsed version.                                             */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*    hInstance        -    Instance handle                                             */
/*    pInData            -    Pointer to the input data stream                          */
/*  *pToProcess        -    Pointer to the start of data processing                     */
/*  *pProcessed        -    Pointer to the destination of the processed data            */
/*    pNumSamples        -    Pointer to the number of samples to process               */
/*                                                                                      */
/* RETURNS:                                                                             */
/*    None                                                                              */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/

void LVM_BufferOptimisedIn(LVM_Handle_t         hInstance,
                           const LVM_INT16      *pInData,
                           LVM_INT16            **pToProcess,
                           LVM_INT16            **pProcessed,
                           LVM_UINT16           *pNumSamples)
{

    LVM_Instance_t   *pInstance = (LVM_Instance_t  *)hInstance;
    LVM_Buffer_t     *pBuffer    = pInstance->pBufferManagement;
    LVM_INT16        *pDest;
    LVM_INT16        SampleCount;
    LVM_INT16        NumSamples;
    LVM_INT16        NumFrames;

    /*
     * Check if it is the first call for this block
     */
    if (pInstance->SamplesToProcess == 0)
    {
        /*
         * First call for a new block of samples
         */
        pBuffer->BufferState = LVM_FIRSTCALL;
        pInstance->pInputSamples    = (LVM_INT16 *)pInData;
        pInstance->SamplesToProcess = (LVM_INT16)*pNumSamples;
        pBuffer->SamplesToOutput    = (LVM_INT16)*pNumSamples;
        pDest = *pProcessed;                                    /* The start of the output buffer */


        /*
         * Copy the already processed samples to the output buffer
         */
        if (pBuffer->OutDelaySamples != 0)
        {
            Copy_16(&pBuffer->OutDelayBuffer[0],                    /* Source */
                    pDest,                                          /* Detsination */
                    (LVM_INT16)(2*pBuffer->OutDelaySamples));       /* Number of delay samples */
            pDest += 2 * pBuffer->OutDelaySamples;                  /* Update the output pointer */
            pBuffer->SamplesToOutput = (LVM_INT16)(pBuffer->SamplesToOutput - pBuffer->OutDelaySamples); /* Update the numbr of samples to output */
        }
        *pToProcess = pDest;                                    /* Set the address to start processing */
        *pProcessed = pDest;                                    /* Process in the output buffer, now inplace */

        /*
         * Copy the input delay buffer (unprocessed) samples to the output buffer
         */
        if (pBuffer->InDelaySamples != 0)
        {
            Copy_16(&pBuffer->InDelayBuffer[0],                     /* Source */
                    pDest,                                          /* Destination */
                    (LVM_INT16)(2*pBuffer->InDelaySamples));        /* Number of delay samples */
            pDest += 2 * pBuffer->InDelaySamples;                   /* Update the output pointer */
        }


        /*
         * Calculate how many input samples to process and copy
         */
        NumSamples    = (LVM_INT16)(*pNumSamples - pBuffer->OutDelaySamples);  /* Number that will fit in the output buffer */
        if (NumSamples >= pInstance->InternalBlockSize)
        {
            NumSamples = pInstance->InternalBlockSize;
        }
        NumFrames      = (LVM_INT16)(NumSamples >> MIN_INTERNAL_BLOCKSHIFT);
        SampleCount   = (LVM_INT16)(NumFrames << MIN_INTERNAL_BLOCKSHIFT);
        *pNumSamples  = (LVM_UINT16)SampleCount;                                        /* The number of samples to process */
        pBuffer->SamplesToOutput = (LVM_INT16)(pBuffer->SamplesToOutput - SampleCount); /* Update the number of samples to output */
        SampleCount   = (LVM_INT16)(SampleCount - pBuffer->InDelaySamples);             /* The number of samples to copy from the input */


        /*
         * Copy samples from the input buffer and update counts and pointers
         */
        Copy_16(pInstance->pInputSamples,                           /* Source */
                pDest,                                              /* Destination */
                (LVM_INT16)(2*SampleCount));                        /* Number of input samples */
        pInstance->pInputSamples += 2 * SampleCount;                /* Update the input pointer */
        pInstance->pOutputSamples = pDest + (2 * SampleCount);      /* Update the output pointer */
        pInstance->SamplesToProcess = (LVM_INT16)(pInstance->SamplesToProcess - SampleCount); /* Samples left in the input buffer */
    }
    else
    {
        /*
         * Second or subsequent call in optimised mode
         */
        if (pBuffer->SamplesToOutput >= MIN_INTERNAL_BLOCKSIZE)
        {
            /*
             * More samples can be processed directly in the output buffer
             */
            *pToProcess = pInstance->pOutputSamples;                /* Set the address to start processing */
            *pProcessed = pInstance->pOutputSamples;                /* Process in the output buffer, now inplace */
            NumSamples  = pBuffer->SamplesToOutput;                 /* Number that will fit in the output buffer */
            if (NumSamples >= pInstance->InternalBlockSize)
            {
                NumSamples = pInstance->InternalBlockSize;
            }
            NumFrames      = (LVM_INT16)(NumSamples >> MIN_INTERNAL_BLOCKSHIFT);
            SampleCount   = (LVM_INT16)(NumFrames << MIN_INTERNAL_BLOCKSHIFT);
            *pNumSamples  = (LVM_UINT16)SampleCount;            /* The number of samples to process */


            /*
             * Copy samples from the input buffer and update counts and pointers
             */
            Copy_16(pInstance->pInputSamples,                       /* Source */
                    pInstance->pOutputSamples,                      /* Destination */
                    (LVM_INT16)(2*SampleCount));                    /* Number of input samples */
            pInstance->pInputSamples += 2 * SampleCount;            /* Update the input pointer */
            pInstance->pOutputSamples += 2 * SampleCount;           /* Update the output pointer */
            pInstance->SamplesToProcess = (LVM_INT16)(pInstance->SamplesToProcess - SampleCount);   /* Samples left in the input buffer */
            pBuffer->SamplesToOutput = (LVM_INT16)(pBuffer->SamplesToOutput - SampleCount);         /* Number that will fit in the output buffer */
        }
        else
        {
            /*
             * The remaining samples can not be processed in the output buffer
             */
            pBuffer->BufferState = LVM_LASTCALL;                    /* Indicate this is the last bock to process */
            *pToProcess  = pBuffer->pScratch;                       /* Set the address to start processing */
            *pProcessed  = pBuffer->pScratch;                       /* Process in the output buffer, now inplace */
            NumSamples   = pInstance->SamplesToProcess;             /* Number left to be processed */
            NumFrames     = (LVM_INT16)(NumSamples >> MIN_INTERNAL_BLOCKSHIFT);
            SampleCount  = (LVM_INT16)(NumFrames << MIN_INTERNAL_BLOCKSHIFT);
            *pNumSamples = (LVM_UINT16)SampleCount;                /* The number of samples to process */


            /*
             * Copy samples from the input buffer and update counts and pointers
             */
            Copy_16(pInstance->pInputSamples,                       /* Source */
                    pBuffer->pScratch,                              /* Destination */
                    (LVM_INT16)(2*SampleCount));                    /* Number of input samples */
            pInstance->pInputSamples += 2 * SampleCount;            /* Update the input pointer */
            pInstance->SamplesToProcess = (LVM_INT16)(pInstance->SamplesToProcess - SampleCount); /* Samples left in the input buffer */
        }
    }
}

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVM_BufferIn                                               */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*    This function manages the data input, it has the following features:              */
/*        - Accepts data in 16-bit aligned memory                                       */
/*        - Copies the data to 32-bit aligned memory                                    */
/*        - Converts Mono inputs to Mono-in-Stereo                                      */
/*        - Accepts any number of samples as input, except 0                            */
/*        - Breaks the input sample stream in to blocks of the configured frame size or */
/*          multiples of the frame size                                                 */
/*        - Limits the processing block size to the maximum block size.                 */
/*        - Works with inplace or outplace processing automatically                     */
/*                                                                                      */
/*  To manage the data the function has a number of operating states:                   */
/*        LVM_FIRSTCALL        - The first call for this block of input samples         */
/*        LVM_MAXBLOCKCALL    - The current block is the maximum size. Only used for the */
/*                              second and subsequent blocks.                           */
/*        LVM_LASTCALL        - The last call for this block of input samples           */
/*        LVM_FIRSTLASTCALL    - This is the first and last call for this block of input*/
/*                              samples, this occurs when the number of samples to      */
/*                              process is less than the maximum block size.            */
/*                                                                                      */
/*    The function uses an internal delay buffer the size of the minimum frame, this is */
/*  used to temporarily hold samples when the number of samples to process is not a     */
/*  multiple of the frame size.                                                         */
/*                                                                                      */
/*    To ensure correct operation with inplace buffering the number of samples to output*/
/*  per call is calculated in this function and is set to the number of samples read    */
/*  from the input buffer.                                                              */
/*                                                                                      */
/*    The total number of samples to process is stored when the function is called for  */
/*  the first time. The value is overwritten by the size of the block to be processed   */
/*  in each call so the size of the processing blocks can be controlled. The number of  */
/*    samples actually processed for each block of input samples is always a multiple of*/
/*  the frame size so for any particular block of input samples the actual number of    */
/*  processed samples may not match the number of input samples, sometime it will be    */
/*  sometimes less. The average is the same and the difference is never more than the   */
/*  frame size.                                                                         */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*    hInstance        -    Instance handle                                             */
/*    pInData            -    Pointer to the input data stream                          */
/*  *pToProcess        -    Pointer to the start of data processing                     */
/*  *pProcessed        -    Pointer to the destination of the processed data            */
/*    pNumSamples        -    Pointer to the number of samples to process               */
/*                                                                                      */
/* RETURNS:                                                                             */
/*    None                                                                              */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/

void LVM_BufferIn(LVM_Handle_t      hInstance,
                  const LVM_INT16   *pInData,
                  LVM_INT16         **pToProcess,
                  LVM_INT16         **pProcessed,
                  LVM_UINT16        *pNumSamples)
{

    LVM_Instance_t    *pInstance = (LVM_Instance_t  *)hInstance;


    /*
     * Check which mode, managed or unmanaged
     */
    if (pInstance->InstParams.BufferMode == LVM_MANAGED_BUFFERS)
    {
        LVM_BufferManagedIn(hInstance,
                            pInData,
                            pToProcess,
                            pProcessed,
                            pNumSamples);
    }
    else
    {
        LVM_BufferUnmanagedIn(hInstance,
                              pToProcess,
                              pProcessed,
                              pNumSamples);
    }
}

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVM_BufferManagedOut                                       */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Full buffer management output. This works in conjunction with the managed input     */
/*  routine and ensures the correct number of samples are always output to the output   */
/*  buffer.                                                                             */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*    hInstance        - Instance handle                                                */
/*    pOutData        - Pointer to the output data stream                               */
/*    pNumSamples        - Pointer to the number of samples to process                  */
/*                                                                                      */
/* RETURNS:                                                                             */
/*    None                                                                              */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/

void LVM_BufferManagedOut(LVM_Handle_t        hInstance,
                          LVM_INT16            *pOutData,
                          LVM_UINT16        *pNumSamples)
{

    LVM_Instance_t  *pInstance  = (LVM_Instance_t  *)hInstance;
    LVM_Buffer_t    *pBuffer    = pInstance->pBufferManagement;
    LVM_INT16       SampleCount = (LVM_INT16)*pNumSamples;
    LVM_INT16       NumSamples;
    LVM_INT16       *pStart;
    LVM_INT16       *pDest;


    /*
     * Set the pointers
     */
    NumSamples = pBuffer->SamplesToOutput;
    pStart     = pBuffer->pScratch;


    /*
     * check if it is the first call of a block
      */
    if ((pBuffer->BufferState == LVM_FIRSTCALL) ||
        (pBuffer->BufferState == LVM_FIRSTLASTCALL))
    {
        /* First call for a new block */
        pInstance->pOutputSamples = pOutData;                        /* Initialise the destination */
    }
    pDest = pInstance->pOutputSamples;                               /* Set the output address */


    /*
     * If the number of samples is non-zero then there are still samples to send to
     * the output buffer
     */
    if ((NumSamples != 0) &&
        (pBuffer->OutDelaySamples != 0))
    {
        /*
         * Copy the delayed output buffer samples to the output
         */
        if (pBuffer->OutDelaySamples <= NumSamples)
        {
            /*
             * Copy all output delay samples to the output
             */
            Copy_16(&pBuffer->OutDelayBuffer[0],                    /* Source */
                    pDest,                                          /* Detsination */
                    (LVM_INT16)(2*pBuffer->OutDelaySamples));       /* Number of delay samples */

            /*
             * Update the pointer and sample counts
             */
            pDest += 2*pBuffer->OutDelaySamples;                                /* Output sample pointer */
            NumSamples = (LVM_INT16)(NumSamples - pBuffer->OutDelaySamples);    /* Samples left to send */
            pBuffer->OutDelaySamples = 0;                                       /* No samples left in the buffer */

        }
        else
        {
            /*
             * Copy only some of the ouput delay samples to the output
             */
            Copy_16(&pBuffer->OutDelayBuffer[0],                    /* Source */
                    pDest,                                          /* Detsination */
                    (LVM_INT16)(2*NumSamples));                     /* Number of delay samples */

            /*
             * Update the pointer and sample counts
             */
            pDest += 2*NumSamples;                                                              /* Output sample pointer */
            pBuffer->OutDelaySamples = (LVM_INT16)(pBuffer->OutDelaySamples - NumSamples);      /* No samples left in the buffer */


            /*
             * Realign the delay buffer data to avoid using circular buffer management
             */
            Copy_16(&pBuffer->OutDelayBuffer[2*NumSamples],         /* Source */
                    &pBuffer->OutDelayBuffer[0],                    /* Destination */
                    (LVM_INT16)(2*pBuffer->OutDelaySamples));       /* Number of samples to move */
            NumSamples = 0;                                         /* Samples left to send */
        }
    }


    /*
     * Copy the processed results to the output
     */
    if ((NumSamples != 0) &&
        (SampleCount != 0))
    {
        if (SampleCount <= NumSamples)
        {
            /*
             * Copy all processed samples to the output
             */
            Copy_16(pStart,                                      /* Source */
                    pDest,                                       /* Detsination */
                    (LVM_INT16)(2*SampleCount));                 /* Number of processed samples */

            /*
             * Update the pointer and sample counts
             */
            pDest      += 2 * SampleCount;                          /* Output sample pointer */
            NumSamples  = (LVM_INT16)(NumSamples - SampleCount);    /* Samples left to send */
            SampleCount = 0;                                        /* No samples left in the buffer */
        }
        else
        {
            /*
             * Copy only some processed samples to the output
             */
            Copy_16(pStart,                                         /* Source */
                    pDest,                                          /* Destination */
                    (LVM_INT16)(2*NumSamples));                     /* Number of processed samples */


            /*
             * Update the pointers and sample counts
               */
            pStart      += 2 * NumSamples;                          /* Processed sample pointer */
            pDest        += 2 * NumSamples;                         /* Output sample pointer */
            SampleCount  = (LVM_INT16)(SampleCount - NumSamples);   /* Processed samples left */
            NumSamples   = 0;                                       /* Clear the sample count */
        }
    }


    /*
     * Copy the remaining processed data to the output delay buffer
     */
    if (SampleCount != 0)
    {
        Copy_16(pStart,                                                 /* Source */
                &pBuffer->OutDelayBuffer[2*pBuffer->OutDelaySamples],   /* Destination */
                (LVM_INT16)(2*SampleCount));                            /* Number of processed samples */
        pBuffer->OutDelaySamples = (LVM_INT16)(pBuffer->OutDelaySamples + SampleCount); /* Update the buffer count */
    }


    /*
     * pointers, counts and set default buffer processing
     */
    pBuffer->SamplesToOutput  = NumSamples;                         /* Samples left to send */
    pInstance->pOutputSamples = pDest;                              /* Output sample pointer */
    pBuffer->BufferState      = LVM_MAXBLOCKCALL;                   /* Set for the default call block size */
    *pNumSamples = (LVM_UINT16)pInstance->SamplesToProcess;         /* This will terminate the loop when all samples processed */
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVM_BufferUnmanagedOut                                     */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This works in conjunction with the unmanaged input routine and updates the number   */
/*    of samples left to be processed    and adjusts the buffer pointers.               */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*    hInstance        - Instance handle                                                */
/*    pNumSamples        - Pointer to the number of samples to process                  */
/*                                                                                      */
/* RETURNS:                                                                             */
/*    None                                                                              */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/

void LVM_BufferUnmanagedOut(LVM_Handle_t        hInstance,
                            LVM_UINT16          *pNumSamples)
{

    LVM_Instance_t      *pInstance  = (LVM_Instance_t  *)hInstance;
    LVM_INT16           NumChannels =2;


    /*
     * Update sample counts
     */
    pInstance->pInputSamples    += (LVM_INT16)(*pNumSamples * NumChannels); /* Update the I/O pointers */
    pInstance->pOutputSamples   += (LVM_INT16)(*pNumSamples * 2);
    pInstance->SamplesToProcess  = (LVM_INT16)(pInstance->SamplesToProcess - *pNumSamples); /* Update the sample count */

    /*
     * Set te block size to process
     */
    if (pInstance->SamplesToProcess > pInstance->InternalBlockSize)
    {
        *pNumSamples = (LVM_UINT16)pInstance->InternalBlockSize;
    }
    else
    {
        *pNumSamples = (LVM_UINT16)pInstance->SamplesToProcess;
    }
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVM_BufferOptimisedOut                                     */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This works in conjunction with the optimised input routine and copies the last few  */
/*  processed and unprocessed samples to their respective buffers.                      */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*    hInstance        - Instance handle                                                */
/*    pNumSamples        - Pointer to the number of samples to process                  */
/*                                                                                      */
/* RETURNS:                                                                             */
/*    None                                                                              */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/

void LVM_BufferOptimisedOut(LVM_Handle_t    hInstance,
                            LVM_UINT16        *pNumSamples)
{

    LVM_Instance_t      *pInstance = (LVM_Instance_t  *)hInstance;
    LVM_Buffer_t        *pBuffer   = pInstance->pBufferManagement;

    /*
     * Check if it is the last block to process
     */
    if (pBuffer->BufferState == LVM_LASTCALL)
    {
        LVM_INT16    *pSrc = pBuffer->pScratch;

        /*
         * Copy the unprocessed samples to the input delay buffer
         */
        if (pInstance->SamplesToProcess != 0)
        {
            Copy_16(pInstance->pInputSamples,                       /* Source */
                    &pBuffer->InDelayBuffer[0],                     /* Destination */
                    (LVM_INT16)(2*pInstance->SamplesToProcess));    /* Number of input samples */
            pBuffer->InDelaySamples = pInstance->SamplesToProcess;
            pInstance->SamplesToProcess = 0;
        }
        else
        {
            pBuffer->InDelaySamples = 0;
        }


        /*
         * Fill the last empty spaces in the output buffer
         */
        if (pBuffer->SamplesToOutput != 0)
        {
            Copy_16(pSrc,                                           /* Source */
                    pInstance->pOutputSamples,                      /* Destination */
                    (LVM_INT16)(2*pBuffer->SamplesToOutput));       /* Number of input samples */
            *pNumSamples = (LVM_UINT16)(*pNumSamples - pBuffer->SamplesToOutput);
            pSrc += 2 * pBuffer->SamplesToOutput;                  /* Update scratch pointer */
            pBuffer->SamplesToOutput = 0;                          /* No more samples in this block */
        }


        /*
         * Save any remaining processed samples in the output delay buffer
         */
        if (*pNumSamples != 0)
        {
            Copy_16(pSrc,                                           /* Source */
                    &pBuffer->OutDelayBuffer[0],                    /* Destination */
                    (LVM_INT16)(2**pNumSamples));                   /* Number of input samples */

            pBuffer->OutDelaySamples = (LVM_INT16)*pNumSamples;

            *pNumSamples = 0;                                      /* No more samples in this block */
        }
        else
        {
            pBuffer->OutDelaySamples = 0;
        }
    }
}


/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                 LVM_BufferOut                                              */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  This function manages the data output, it has the following features:               */
/*        - Output data to 16-bit aligned memory                                        */
/*        - Reads data from 32-bit aligned memory                                       */
/*        - Reads data only in blocks of frame size or multiples of frame size          */
/*        - Writes the same number of samples as the LVM_BufferIn function reads        */
/*        - Works with inplace or outplace processing automatically                     */
/*                                                                                      */
/*  To manage the data the function has a number of operating states:                   */
/*        LVM_FIRSTCALL        - The first call for this block of input samples         */
/*        LVM_FIRSTLASTCALL    - This is the first and last call for this block of input*/
/*                              samples, this occurs when the number of samples to      */
/*                              process is less than the maximum block size.            */
/*                                                                                      */
/*    The function uses an internal delay buffer the size of the minimum frame, this is */
/*  used to temporarily hold samples when the number of samples to write is not a       */
/*  multiple of the frame size.                                                         */
/*                                                                                      */
/*    To ensure correct operation with inplace buffering the number of samples to output*/
/*  per call is always the same as the number of samples read from the input buffer.    */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*    hInstance        - Instance handle                                                */
/*    pOutData        - Pointer to the output data stream                               */
/*    pNumSamples        - Pointer to the number of samples to process                  */
/*                                                                                      */
/* RETURNS:                                                                             */
/*    None                                                                              */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/

void LVM_BufferOut(LVM_Handle_t     hInstance,
                   LVM_INT16        *pOutData,
                   LVM_UINT16       *pNumSamples)
{

    LVM_Instance_t    *pInstance  = (LVM_Instance_t  *)hInstance;


    /*
     * Check which mode, managed or unmanaged
     */
    if (pInstance->InstParams.BufferMode == LVM_MANAGED_BUFFERS)
    {
        LVM_BufferManagedOut(hInstance,
                             pOutData,
                             pNumSamples);
    }
    else
    {
        LVM_BufferUnmanagedOut(hInstance,
                               pNumSamples);
    }
}

