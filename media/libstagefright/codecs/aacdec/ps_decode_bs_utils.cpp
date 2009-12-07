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

 Filename: ps_decode_bs_utils.c

  Functions:
        GetNrBitsAvailable
        differential_Decoding
        map34IndexTo20
        limitMinMax

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

        Decode bitstream parametric stereo's utilities

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

#include "aac_mem_funcs.h"
#include "s_ps_dec.h"
#include "ps_decode_bs_utils.h"

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

Int32 GetNrBitsAvailable(HANDLE_BIT_BUFFER hBitBuf)
{

    return (hBitBuf->bufferLen - hBitBuf->nrBitsRead);
}

/*----------------------------------------------------------------------------
; FUNCTION CODE
;
;   Differential Decoding of parameters over time/frequency
----------------------------------------------------------------------------*/

void differential_Decoding(Int32 enable,
                           Int32 *aIndex,
                           Int32 *aPrevFrameIndex,
                           Int32 DtDf,
                           Int32 nrElements,
                           Int32 stride,
                           Int32 minIdx,
                           Int32 maxIdx)
{
    Int32 i;
    Int32 *ptr_aIndex;

    if (enable == 1)
    {
        ptr_aIndex = aIndex;

        if (DtDf == 0)
        {
            *(ptr_aIndex) = limitMinMax(*ptr_aIndex, minIdx, maxIdx);
            ptr_aIndex++;

            for (i = 1; i < nrElements; i++)
            {
                *(ptr_aIndex) = limitMinMax(aIndex[i-1] + *ptr_aIndex, minIdx, maxIdx);
                ptr_aIndex++;
            }
        }
        else
        {
            if (stride == 1)
            {
                for (i = 0; i < nrElements; i++)
                {
                    *(ptr_aIndex) = limitMinMax(aPrevFrameIndex[i] + *ptr_aIndex, minIdx, maxIdx);
                    ptr_aIndex++;
                }
            }
            else
            {
                for (i = 0; i < nrElements; i++)
                {
                    *(ptr_aIndex) = limitMinMax(aPrevFrameIndex[(i<<1)] + *ptr_aIndex, minIdx, maxIdx);
                    ptr_aIndex++;
                }
            }
        }
    }
    else
    {
        pv_memset((void *)aIndex, 0, nrElements*sizeof(*aIndex));
    }
    if (stride == 2)
    {
        for (i = (nrElements << 1) - 1; i > 0; i--)
        {
            aIndex[i] = aIndex[(i>>1)];
        }
    }
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
        map34IndexTo20
----------------------------------------------------------------------------*/

void map34IndexTo20(Int32 *aIndex)
{

    aIndex[ 0] = ((aIndex[0] << 1) +  aIndex[1]) / 3;
    aIndex[ 1] = (aIndex[1] + (aIndex[2] << 1)) / 3;
    aIndex[ 2] = ((aIndex[3] << 1) +  aIndex[4]) / 3;
    aIndex[ 3] = (aIndex[4] + (aIndex[5] << 1)) / 3;
    aIndex[ 4] = (aIndex[ 6] +  aIndex[7]) >> 1;
    aIndex[ 5] = (aIndex[ 8] +  aIndex[9]) >> 1;
    aIndex[ 6] =   aIndex[10];
    aIndex[ 7] =   aIndex[11];
    aIndex[ 8] = (aIndex[12] +  aIndex[13]) >> 1;
    aIndex[ 9] = (aIndex[14] +  aIndex[15]) >> 1;
    aIndex[10] =   aIndex[16];
    aIndex[11] =   aIndex[17];
    aIndex[12] =   aIndex[18];
    aIndex[13] =   aIndex[19];
    aIndex[14] = (aIndex[20] +  aIndex[21]) >> 1;
    aIndex[15] = (aIndex[22] +  aIndex[23]) >> 1;
    aIndex[16] = (aIndex[24] +  aIndex[25]) >> 1;
    aIndex[17] = (aIndex[26] +  aIndex[27]) >> 1;
    aIndex[18] = (aIndex[28] +  aIndex[29] + aIndex[30] + aIndex[31]) >> 2;
    aIndex[19] = (aIndex[32] +  aIndex[33]) >> 1;
}


/*----------------------------------------------------------------------------
; FUNCTION CODE
        limitMinMax
----------------------------------------------------------------------------*/


Int32 limitMinMax(Int32 i,
                  Int32 min,
                  Int32 max)
{
    if (i < max)
    {
        if (i > min)
        {
            return i;
        }
        else
        {
            return min;
        }
    }
    else
    {
        return max;
    }
}


#endif


#endif

