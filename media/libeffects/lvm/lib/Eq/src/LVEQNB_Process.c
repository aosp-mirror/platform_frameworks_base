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
/*  Includes                                                                            */
/*                                                                                      */
/****************************************************************************************/

#include "LVEQNB.h"
#include "LVEQNB_Private.h"
#include "VectorArithmetic.h"
#include "BIQUAD.h"


/****************************************************************************************/
/*                                                                                      */
/*  Defines                                                                             */
/*                                                                                      */
/****************************************************************************************/

#define SHIFT       13

/****************************************************************************************/
/*                                                                                      */
/* FUNCTION:                LVEQNB_Process                                              */
/*                                                                                      */
/* DESCRIPTION:                                                                         */
/*  Process function for the N-Band Equaliser module.                                   */
/*                                                                                      */
/* PARAMETERS:                                                                          */
/*  hInstance               Instance handle                                             */
/*  pInData                 Pointer to the input data                                   */
/*  pOutData                Pointer to the output data                                  */
/*  NumSamples              Number of samples in the input buffer                       */
/*                                                                                      */
/* RETURNS:                                                                             */
/*  LVEQNB_SUCCESS          Succeeded                                                   */
/*  LVEQNB_NULLADDRESS      When hInstance, pInData or pOutData are NULL                */
/*  LVEQNB_ALIGNMENTERROR   When pInData or pOutData are not 32-bit aligned             */
/*  LVEQNB_TOOMANYSAMPLES   NumSamples was larger than the maximum block size           */
/*                                                                                      */
/* NOTES:                                                                               */
/*                                                                                      */
/****************************************************************************************/

LVEQNB_ReturnStatus_en LVEQNB_Process(LVEQNB_Handle_t       hInstance,
                                      const LVM_INT16       *pInData,
                                      LVM_INT16             *pOutData,
                                      LVM_UINT16            NumSamples)
{

    LVM_UINT16          i;
    Biquad_Instance_t   *pBiquad;
    LVEQNB_Instance_t   *pInstance = (LVEQNB_Instance_t  *)hInstance;
    LVM_INT32           *pScratch;


     /* Check for NULL pointers */
    if((hInstance == LVM_NULL) || (pInData == LVM_NULL) || (pOutData == LVM_NULL))
    {
        return LVEQNB_NULLADDRESS;
    }

    /* Check if the input and output data buffers are 32-bit aligned */
    if ((((LVM_INT32)pInData % 4) != 0) || (((LVM_INT32)pOutData % 4) != 0))
    {
        return LVEQNB_ALIGNMENTERROR;
    }

    pScratch  = (LVM_INT32 *)pInstance->pFastTemporary;

    /*
    * Check the number of samples is not too large
    */
    if (NumSamples > pInstance->Capabilities.MaxBlockSize)
    {
        return(LVEQNB_TOOMANYSAMPLES);
    }

    if (pInstance->Params.OperatingMode == LVEQNB_ON)
    {
        /*
         * Convert from 16-bit to 32-bit
         */
        Int16LShiftToInt32_16x32((LVM_INT16 *)pInData,      /* Source */
                                 pScratch,                  /* Destination */
                                 (LVM_INT16)(2*NumSamples), /* Left and Right */
                                 SHIFT);                    /* Scaling shift */

        /*
         * For each section execte the filter unless the gain is 0dB
         */
        if (pInstance->NBands != 0)
        {
            for (i=0; i<pInstance->NBands; i++)
            {
                /*
                 * Check if band is non-zero dB gain
                 */
                if (pInstance->pBandDefinitions[i].Gain != 0)
                {
                    /*
                     * Get the address of the biquad instance
                     */
                    pBiquad = &pInstance->pEQNB_FilterState[i];


                    /*
                     * Select single or double precision as required
                     */
                    switch (pInstance->pBiquadType[i])
                    {
                        case LVEQNB_SinglePrecision:
                        {
                            PK_2I_D32F32C14G11_TRC_WRA_01(pBiquad,
                                                          (LVM_INT32 *)pScratch,
                                                          (LVM_INT32 *)pScratch,
                                                          (LVM_INT16)NumSamples);
                            break;
                        }

                        case LVEQNB_DoublePrecision:
                        {
                            PK_2I_D32F32C30G11_TRC_WRA_01(pBiquad,
                                                          (LVM_INT32 *)pScratch,
                                                          (LVM_INT32 *)pScratch,
                                                          (LVM_INT16)NumSamples);
                            break;
                        }
                        default:
                            break;
                    }
                }
            }
        }


        if(pInstance->bInOperatingModeTransition == LVM_TRUE){
                /*
                 * Convert from 32-bit to 16- bit and saturate
                 */
                Int32RShiftToInt16_Sat_32x16(pScratch,                      /* Source */
                                             (LVM_INT16 *)pScratch,         /* Destination */
                                             (LVM_INT16)(2*NumSamples),     /* Left and Right */
                                             SHIFT);                        /* Scaling shift */

                LVC_MixSoft_2St_D16C31_SAT(&pInstance->BypassMixer,
                                                (LVM_INT16 *)pScratch,
                                                (LVM_INT16 *)pInData,
                                                (LVM_INT16 *)pScratch,
                                                (LVM_INT16)(2*NumSamples));

                Copy_16((LVM_INT16*)pScratch,                           /* Source */
                        pOutData,                                       /* Destination */
                        (LVM_INT16)(2*NumSamples));                     /* Left and Right samples */
        }
        else{

            /*
             * Convert from 32-bit to 16- bit and saturate
             */
            Int32RShiftToInt16_Sat_32x16(pScratch,              /* Source */
                                         pOutData,              /* Destination */
                                         (LVM_INT16 )(2*NumSamples), /* Left and Right */
                                         SHIFT);                /* Scaling shift */
        }
    }
    else
    {
        /*
         * Mode is OFF so copy the data if necessary
         */
        if (pInData != pOutData)
        {
            Copy_16(pInData,                                    /* Source */
                    pOutData,                                   /* Destination */
                    (LVM_INT16)(2*NumSamples));                 /* Left and Right samples */
        }
    }



    return(LVEQNB_SUCCESS);

}
