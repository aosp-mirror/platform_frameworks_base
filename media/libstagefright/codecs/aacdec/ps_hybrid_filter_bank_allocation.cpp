/* ------------------------------------------------------------------
 * Copyright (C) 1998-2009 PacketVideo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied.
 * See the License for the specific language governing permissions
 * and limitations under the License.
 * -------------------------------------------------------------------
 */
/*

 Filename: ps_hybrid_filter_bank_allocation.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

        Does Hybrid filter bank memory allocation

------------------------------------------------------------------------------
 REQUIREMENTS


------------------------------------------------------------------------------
 REFERENCES

SC 29 Software Copyright Licencing Disclaimer:

This software module was originally developed by
  Coding Technologies

and edited by
  -

in the course of development of the ISO/IEC 13818-7 and ISO/IEC 14496-3
standards for reference purposes and its performance may not have been
optimized. This software module is an implementation of one or more tools as
specified by the ISO/IEC 13818-7 and ISO/IEC 14496-3 standards.
ISO/IEC gives users free license to this software module or modifications
thereof for use in products claiming conformance to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International
Standards. ISO/IEC gives users the same free license to this software module or
modifications thereof for research purposes and further ISO/IEC standardisation.
Those intending to use this software module in products are advised that its
use may infringe existing patents. ISO/IEC have no liability for use of this
software module or modifications thereof. Copyright is not released for
products that do not conform to audiovisual and image-coding related ITU
Recommendations and/or ISO/IEC International Standards.
The original developer retains full right to modify and use the code for its
own purpose, assign or donate the code to a third party and to inhibit third
parties from using the code for products that do not conform to audiovisual and
image-coding related ITU Recommendations and/or ISO/IEC International Standards.
This copyright notice must be included in all copies or derivative works.
Copyright (c) ISO/IEC 2003.

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#ifdef AAC_PLUS

#ifdef PARAMETRICSTEREO
#include    "aac_mem_funcs.h"
#include    "ps_hybrid_filter_bank_allocation.h"
#include    "ps_all_pass_filter_coeff.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
; LOCAL STORE/BUFFER/POINTER DEFINITIONS
; Variable declaration - defined here and used outside this module
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; EXTERNAL FUNCTION REFERENCES
; Declare functions defined elsewhere and referenced in this module
----------------------------------------------------------------------------*/



/*----------------------------------------------------------------------------
; EXTERNAL GLOBAL STORE/BUFFER/POINTER REFERENCES
; Declare variables used in this module but defined elsewhere
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

Int32 ps_hybrid_filter_bank_allocation(HYBRID **phHybrid,
                                       Int32 noBands,
                                       const Int32 *pResolution,
                                       Int32 **pPtr)
{
    Int32 i;
    Int32 tmp;
    Int32 maxNoChannels = 0;
    HYBRID *hs;
    Int32 *ptr = *pPtr;


    *phHybrid = (HYBRID *)NULL;

    hs = (HYBRID *)ptr;

    ptr += sizeof(HYBRID) / sizeof(*ptr);

    hs->pResolution = (Int32*)ptr;

    ptr += noBands * sizeof(Int32) / sizeof(*ptr);

    for (i = 0; i < noBands; i++)
    {

        hs->pResolution[i] = pResolution[i];

        if (pResolution[i] != HYBRID_8_CPLX &&
                pResolution[i] != HYBRID_2_REAL &&
                pResolution[i] != HYBRID_4_CPLX)
        {
            return 1;
        }

        if (pResolution[i] > maxNoChannels)
        {
            maxNoChannels = pResolution[i];
        }
    }

    hs->nQmfBands     = noBands;
    hs->qmfBufferMove = HYBRID_FILTER_LENGTH - 1;

    hs->mQmfBufferReal = (Int32 **)ptr;
    ptr += noBands * sizeof(ptr) / sizeof(*ptr);

    hs->mQmfBufferImag = (Int32 **)ptr;
    ptr += noBands * sizeof(ptr) / sizeof(*ptr);

    tmp = hs->qmfBufferMove;        /*  HYBRID_FILTER_LENGTH == 13 */

    for (i = 0; i < noBands; i++)
    {
        hs->mQmfBufferReal[i] = ptr;
        ptr += tmp;

        hs->mQmfBufferImag[i] = ptr;
        ptr += tmp;

    }

    hs->mTempReal = ptr;
    ptr += maxNoChannels;


    hs->mTempImag = ptr;
    ptr += maxNoChannels;


    *phHybrid = hs;
    *pPtr = ptr;

    return 0;
}


#endif


#endif

