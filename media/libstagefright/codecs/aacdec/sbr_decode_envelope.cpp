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

 Filename: sbr_decode_envelope.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION


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
Copyright (c) ISO/IEC 2002.

------------------------------------------------------------------------------
 PSEUDO-CODE

------------------------------------------------------------------------------
*/


/*----------------------------------------------------------------------------
; INCLUDES
----------------------------------------------------------------------------*/

#ifdef AAC_PLUS


#include    "sbr_decode_envelope.h"
#include    "sbr_constants.h"

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
void mapLowResEnergyVal(
    Int32  currVal,
    Int32 *prevData,
    Int32 offset,
    Int32 index,
    Int32 res);

Int32 indexLow2High(Int32 offset,
                    Int32 index,
                    Int32 res);

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


void sbr_decode_envelope(SBR_FRAME_DATA * hFrameData)

{
    Int32 i;
    Int32 no_of_bands;
    Int32 band;
    Int32 freqRes;
    Int32 *iEnvelope    = hFrameData->iEnvelope_man;
    Int32 *sfb_nrg_prev = hFrameData->sfb_nrg_prev_man;

    Int32  offset       = hFrameData->offset;
    Int32 *nSfb         = hFrameData->nSfb;
    Int32 *domain_vec   = hFrameData->domain_vec1;
    Int32 *frameInfo    = hFrameData->frameInfo;



    for (i = 0; i < frameInfo[0]; i++)
    {
        freqRes = frameInfo[frameInfo[0] + i + 2];
        no_of_bands = nSfb[freqRes];

        if (domain_vec[i] == 0)
        {
            mapLowResEnergyVal(*iEnvelope,
                               sfb_nrg_prev,
                               offset,
                               0,
                               freqRes);
            iEnvelope++;

            for (band = 1; band < no_of_bands; band++)
            {
                *iEnvelope +=  *(iEnvelope - 1);

                mapLowResEnergyVal(*iEnvelope,
                                   sfb_nrg_prev,
                                   offset,
                                   band,
                                   freqRes);
                iEnvelope++;
            }
        }
        else
        {
            for (band = 0; band < no_of_bands; band++)
            {
                *iEnvelope +=  sfb_nrg_prev[ indexLow2High(offset, band, freqRes)];

                mapLowResEnergyVal(*iEnvelope,
                                   sfb_nrg_prev,
                                   offset,
                                   band,
                                   freqRes);
                iEnvelope++;
            }
        }
    }
}



void mapLowResEnergyVal(
    Int32  currVal,
    Int32 *prevData,
    Int32  offset,
    Int32  index,
    Int32  res)
{
    Int32 tmp;

    if (res == LO)
    {
        if (offset >= 0)
        {
            if (index < offset)
            {
                prevData[index] = currVal;
            }
            else
            {
                tmp = (index << 1) - offset;
                prevData[tmp    ] = currVal;
                prevData[tmp +1 ] = currVal;
            }
        }
        else
        {
            offset = -offset;
            if (index < offset)
            {
                tmp = (index << 1) + index;
                prevData[tmp    ] = currVal;
                prevData[tmp + 1] = currVal;
                prevData[tmp + 2] = currVal;
            }
            else
            {
                tmp = (index << 1) + offset;
                prevData[tmp    ] = currVal;
                prevData[tmp + 1] = currVal;
            }
        }
    }
    else
    {
        prevData[index] = currVal;
    }
}


Int32 indexLow2High(Int32 offset,
                    Int32 index,
                    Int32 res)
{
    if (res == LO)
    {
        if (offset >= 0)
        {
            if (index < offset)
            {
                return(index);
            }
            else
            {
                return((index << 1) - offset);
            }
        }
        else
        {
            offset = -offset;
            if (index < offset)
            {
                return((index << 1) + index);
            }
            else
            {
                return((index << 1) + offset);
            }
        }
    }
    else
    {
        return(index);
    }
}

#endif

