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

/****************************************************************************************

     $Author: nxp007753 $
     $Revision: 1081 $
     $Date: 2010-07-05 11:48:44 +0200 (Mon, 05 Jul 2010) $

*****************************************************************************************/

/****************************************************************************************/
/*                                                                                      */
/*    Includes                                                                          */
/*                                                                                      */
/****************************************************************************************/

#include "LVDBE.h"
#include "LVDBE_Private.h"
#include "VectorArithmetic.h"
#include "AGC.h"
#include "LVDBE_Coeffs.h"               /* Filter coefficients */


/********************************************************************************************/
/*                                                                                          */
/* FUNCTION:                 LVDBE_Process                                                  */
/*                                                                                          */
/* DESCRIPTION:                                                                             */
/*  Process function for the Bass Enhancement module.                                       */
/*                                                                                          */
/*  Data can be processed in two formats, stereo or mono-in-stereo. Data in mono            */
/*  format is not supported, the calling routine must convert the mono stream to            */
/*  mono-in-stereo.                                                                         */
/*                                                        ___________                       */
/*       ________                                        |           |    ________          */
/*      |        |    _____   |------------------------->|           |   |        |         */
/*      | 16-bit |   |     |  |    ________              |           |   | 32-bit |         */
/* -+-->|   to   |-->| HPF |--|   |        |    _____    | AGC Mixer |-->|   to   |--|      */
/*  |   | 32-bit |   |_____|  |   | Stereo |   |     |   |           |   | 16-bit |  |      */
/*  |   |________|            |-->|   to   |-->| BPF |-->|           |   |________|  0      */
/*  |                             |  Mono  |   |_____|   |___________|                \-->  */
/*  |                             |________|                                                */
/*  |                                                     _________                  0      */
/*  |                                                    |         |                 |      */
/*  |----------------------------------------------------| Volume  |-----------------|      */
/*                                                       | Control |                        */
/*                                                       |_________|                        */
/*                                                                                          */
/* PARAMETERS:                                                                              */
/*  hInstance                 Instance handle                                               */
/*  pInData                  Pointer to the input data                                      */
/*  pOutData                 Pointer to the output data                                     */
/*  NumSamples                 Number of samples in the input buffer                        */
/*                                                                                          */
/* RETURNS:                                                                                 */
/*  LVDBE_SUCCESS            Succeeded                                                      */
/*    LVDBE_TOOMANYSAMPLES    NumSamples was larger than the maximum block size             */
/*                                                                                          */
/* NOTES:                                                                                   */
/*  1. The input and output data must be 32-bit format. The input is scaled by a shift      */
/*     when converting from 16-bit format, this scaling allows for internal headroom in the */
/*     bass enhancement algorithm.                                                          */
/*  2. For a 16-bit implementation the converstion to 32-bit is removed and replaced with   */
/*     the headroom loss. This headroom loss is compensated in the volume control so the    */
/*     overall end to end gain is odB.                                                      */
/*                                                                                          */
/********************************************************************************************/

LVDBE_ReturnStatus_en LVDBE_Process(LVDBE_Handle_t            hInstance,
                                       const LVM_INT16         *pInData,
                                       LVM_INT16               *pOutData,
                                       LVM_UINT16                   NumSamples)
{

    LVDBE_Instance_t    *pInstance =(LVDBE_Instance_t  *)hInstance;
    LVM_INT32           *pScratch  = (LVM_INT32 *)pInstance->MemoryTable.Region[LVDBE_MEMREGION_SCRATCH].pBaseAddress;
    LVM_INT32           *pMono     = (LVM_INT32 *)pOutData;
    LVM_INT16           *pInput    = (LVM_INT16 *)pInData;


    /*
     * Check the number of samples is not too large
     */
    if (NumSamples > pInstance->Capabilities.MaxBlockSize)
    {
        return(LVDBE_TOOMANYSAMPLES);
    }

    /*
     * Check if the algorithm is enabled
     */
    if ((pInstance->Params.OperatingMode != LVDBE_OFF) ||
        (pInstance->bTransitionOnToOff == LVM_TRUE))
    {

        /*
         * Convert 16-bit samples to 32-bit and scale
         * (For a 16-bit implementation apply headroom loss here)
         */
        Int16LShiftToInt32_16x32(pInput,                               /* Source 16-bit data      */
                                 pScratch,                             /* Destination 32-bit data */
                                 (LVM_INT16)(2*NumSamples),            /* Left and right          */
                                 LVDBE_SCALESHIFT);                    /* Shift scale             */


        /*
         * Apply the high pass filter if selected
         */
        if (pInstance->Params.HPFSelect == LVDBE_HPF_ON)
        {
              BQ_2I_D32F32C30_TRC_WRA_01(&pInstance->pCoef->HPFInstance,    /* Filter instance         */
                                       (LVM_INT32 *)pScratch,               /* Source                  */
                                       (LVM_INT32 *)pScratch,               /* Destination             */
                                       (LVM_INT16)NumSamples);              /* Number of samples       */
        }


        /*
         * Create the mono stream
         */
        From2iToMono_32(pScratch,                                      /* Stereo source           */
                        pMono,                                         /* Mono destination        */
                        (LVM_INT16)NumSamples);                        /* Number of samples       */


        /*
         * Apply the band pass filter
         */
          BP_1I_D32F32C30_TRC_WRA_02(&pInstance->pCoef->BPFInstance,     /* Filter instance         */
                                   (LVM_INT32 *)pMono,                 /* Source                  */
                                   (LVM_INT32 *)pMono,                 /* Destination             */
                                   (LVM_INT16)NumSamples);             /* Number of samples       */


        /*
         * Apply the AGC and mix
         */
        AGC_MIX_VOL_2St1Mon_D32_WRA(&pInstance->pData->AGCInstance,    /* Instance pointer        */
                                    pScratch,                          /* Stereo source           */
                                    pMono,                             /* Mono band pass source   */
                                    pScratch,                          /* Stereo destination      */
                                    NumSamples);                       /* Number of samples       */

        if(pInstance->bTransitionOnToOff == LVM_TRUE)
        {
            if ((pInstance->pData->AGCInstance.AGC_Gain == pInstance->pData->AGCInstance.AGC_Target)&&
                (pInstance->pData->AGCInstance.AGC_Gain == 0))
            {
                    pInstance->bTransitionOnToOff = LVM_FALSE;
            }
        }



        /*
         * Convert 32-bit samples to 16-bit and saturate
         * (Not required for 16-bit implemenations)
         */
        Int32RShiftToInt16_Sat_32x16(pScratch,                         /* Source 32-bit data      */
                                     pOutData,                         /* Destination 16-bit data */
                                     (LVM_INT16)(2*NumSamples),        /* Left and right          */
                                     LVDBE_SCALESHIFT);                /* Shift scale             */

    }
    else
    {

        /*
         * The algorithm is disabled but volume management is required to compensate for
         * headroom and volume (if enabled)
         */
        LVC_MixSoft_1St_D16C31_SAT(&pInstance->pData->BypassVolume,
                                  pInData,
                               pOutData,
                               (LVM_INT16)(2*NumSamples));           /* Left and right           */

    }

    return(LVDBE_SUCCESS);
}










