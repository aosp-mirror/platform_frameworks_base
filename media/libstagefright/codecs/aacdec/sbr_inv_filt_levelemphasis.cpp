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

 Filename: sbr_inv_filt_levelemphasis.c

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


#include    "sbr_inv_filt_levelemphasis.h"
#include    "sbr_generate_high_freq.h"

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


#include "pv_audio_type_defs.h"
#include "fxp_mul32.h"

#define R_SHIFT     29
#define Qfmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))

const Int32 InvFiltFactors[5] = {Qfmt(0.00f),    /* OFF_LEVEL */
                                 Qfmt(0.60f),     /* TRANSITION_LEVEL */
                                 Qfmt(0.75f),     /* LOW_LEVEL */
                                 Qfmt(0.90f),     /* MID_LEVEL */
                                 Qfmt(0.98f)
                                };    /* HIGH_LEVEL */

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

void sbr_inv_filt_levelemphasis(INVF_MODE *invFiltMode,
                                INVF_MODE *prevInvFiltMode,
                                Int32 nNfb,
                                Int32  BwVector[MAX_NUM_PATCHES],
                                Int32  BwVectorOld[MAX_NUM_PATCHES])
{
    Int32 i;
    Int32 j;
    Int32 tmp;

    for (i = 0; i < nNfb; i++)
    {
        switch (invFiltMode[i])
        {
            case INVF_LOW_LEVEL:
                if (prevInvFiltMode[i] == INVF_OFF)
                {
                    j = 1;
                }
                else
                {
                    j = 2;
                }
                break;

            case INVF_MID_LEVEL:
                j = 3;
                break;

            case INVF_HIGH_LEVEL:
                j = 4;
                break;

            default:
                if (prevInvFiltMode[i] == INVF_LOW_LEVEL)
                {
                    j = 1;
                }
                else
                {
                    j = 0;
                }
        }

        tmp  =  InvFiltFactors[j];

        if (tmp < BwVectorOld[i])
        {
            tmp = ((tmp << 1) + tmp + BwVectorOld[i]) >> 2;
        }
        else
        {
            tmp =  fxp_mul32_Q29(Qfmt(0.90625f), tmp);
            tmp =  fxp_mac32_Q29(Qfmt(0.09375f), BwVectorOld[i], tmp);
        }

        if (tmp < Qfmt(0.015625F))
        {
            tmp = 0;
        }

        if (tmp >= Qfmt(0.99609375f))
        {
            tmp = Qfmt(0.99609375f);
        }

        BwVector[i] = tmp;
    }
}


#endif


