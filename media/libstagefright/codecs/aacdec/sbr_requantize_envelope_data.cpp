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

 Filename: sbr_requantize_envelope_data.c

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


#include    "sbr_constants.h"
#include    "sbr_requantize_envelope_data.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define R_SHIFT     30
#define Qfmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))
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

void sbr_requantize_envelope_data(SBR_FRAME_DATA * hFrameData)

{
    Int32 i;


    Int32  nScaleFactors      =  hFrameData->nScaleFactors;
    Int32  nNoiseFactors      =  hFrameData->nNoiseFactors;
    Int32  ampRes             =  hFrameData->ampRes;
    Int32 *iEnvelope_man      =  hFrameData->iEnvelope_man;
    Int32 *iEnvelope_exp      =  hFrameData->iEnvelope_exp;
    Int32 *sbrNoiseFloorLevel_man = hFrameData->sbrNoiseFloorLevel_man;
    Int32 *sbrNoiseFloorLevel_exp = hFrameData->sbrNoiseFloorLevel_exp;

    /*
     *  ampRes could be 0 (resolution step = 1.5 dB) or
     *                  1 (resolution step = 3 dB)
     */
    if (ampRes)
    {
        /*  iEnvelope[i] always positive  6 bits max */
        for (i = 0; i < nScaleFactors; i++)
        {

            iEnvelope_exp[i] = iEnvelope_man[i] + 6;
            iEnvelope_man[i] = Qfmt(1.000F);
        }
    }
    else
    {
        /*  iEnvelope[i] always positive  7 bits max */
        for (i = 0; i < nScaleFactors; i++)
        {
            iEnvelope_exp[i] = (iEnvelope_man[i] >> 1) + 6;
            if (iEnvelope_man[i] & 0x1)   /*  odd */
            {
                iEnvelope_man[i] = Qfmt(1.41421356237310F);
            }
            else
            {
                iEnvelope_man[i] = Qfmt(1.000F);
            }
        }

    }
    for (i = 0; i < nNoiseFactors; i++)
    {
        /*  sbrNoiseFloorLevel[i] varies from -31 to 31 if no coupling is used */

        sbrNoiseFloorLevel_exp[i] = NOISE_FLOOR_OFFSET - sbrNoiseFloorLevel_man[i];
        sbrNoiseFloorLevel_man[i] = 0x40000000;
    }
}

#endif

