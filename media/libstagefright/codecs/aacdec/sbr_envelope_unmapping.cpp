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

 Filename: sbr_envelope_unmapping.c

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


#include    "sbr_envelope_unmapping.h"
#include    "sbr_constants.h"

#include "fxp_mul32.h"

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

#define R_SHIFT     30
#define Qfmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))

/*
 *  1./(1+2.^-[0:10])
 */
const Int32 one_over_one_plus_two_to_n[11] =
{
    Qfmt(0.50000000000000F), Qfmt(0.66666666666667F), Qfmt(0.80000000000000F),
    Qfmt(0.88888888888889F), Qfmt(0.94117647058824F), Qfmt(0.96969696969697F),
    Qfmt(0.98461538461538F), Qfmt(0.99224806201550F), Qfmt(0.99610894941634F),
    Qfmt(0.99805068226121F), Qfmt(0.99902439024390F)
};

/*
 *  1./(1+2.^[0.5:-1:-10.5])
 */
const Int32 one_over_one_plus_sq_2_by_two_to_n[12] =
{
    Qfmt(0.41421356237310F), Qfmt(0.58578643762690F), Qfmt(0.73879612503626F),
    Qfmt(0.84977889517767F), Qfmt(0.91878969685839F), Qfmt(0.95767628767521F),
    Qfmt(0.97838063800882F), Qfmt(0.98907219289563F), Qfmt(0.99450607818892F),
    Qfmt(0.99724547251514F), Qfmt(0.99862083678608F), Qfmt(0.99930994254211F)
};

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

void sbr_envelope_unmapping(SBR_FRAME_DATA * hFrameData1,
                            SBR_FRAME_DATA * hFrameData2)

{
    Int32 i;
    Int32 tempLeft;
    Int32 tempRight;

    Int32 tmp;
    Int32 *iEnvelopeLeft_man    = hFrameData1->iEnvelope_man;
    Int32 *iEnvelopeLeft_exp    = hFrameData1->iEnvelope_exp;
    Int32 *noiseFloorLeft_man   = hFrameData1->sbrNoiseFloorLevel_man;
    Int32 *noiseFloorLeft_exp   = hFrameData1->sbrNoiseFloorLevel_exp;

    Int32 *iEnvelopeRight_man   = hFrameData2->iEnvelope_man;
    Int32 *iEnvelopeRight_exp   = hFrameData2->iEnvelope_exp;
    Int32 *noiseFloorRight_man  = hFrameData2->sbrNoiseFloorLevel_man;
    Int32 *noiseFloorRight_exp  = hFrameData2->sbrNoiseFloorLevel_exp;


    if (hFrameData2->ampRes)
    {
        for (i = 0; i < hFrameData1->nScaleFactors; i++)
        {
            tempRight = iEnvelopeRight_man[i];
            tempLeft  = iEnvelopeLeft_man[i];
            /*  iEnvelope[i] always positive  6 bits max */

            iEnvelopeLeft_exp[i] = tempLeft + 7;

            iEnvelopeRight_exp[i] = tempRight - 12;
            iEnvelopeRight_man[i] = Qfmt(1.000F);

            /*
             *  iEnvelopeRight[i] = tempLeft / (1 + tempRight);
             *  iEnvelopeLeft[i]  = tempRight * iEnvelopeRight[i];
             *
             *
             *   iEnvelopeRight[i] = k*2^n/(1+2^m) =  k*2^(n-m)/(1 + 2^-m);
             *   where k = 1 or sqrt(2)
             */
            if (iEnvelopeRight_exp[i] >= 0)
            {
                if (iEnvelopeRight_exp[i] < 11)
                {
                    iEnvelopeRight_man[i] = one_over_one_plus_two_to_n[ iEnvelopeRight_exp[i]];
                }
                else        /*  1/(1+2^-m) == 1 - 2^-m ;  for m >= 10  */
                {
                    iEnvelopeRight_man[i] -= (Qfmt(1.000F) >> iEnvelopeRight_exp[i]);
                }
                iEnvelopeRight_exp[i] = iEnvelopeLeft_exp[i] - iEnvelopeRight_exp[i];
            }
            else
            {
                if (iEnvelopeRight_exp[i] > -11)
                {
                    iEnvelopeRight_man[i] -= one_over_one_plus_two_to_n[ -iEnvelopeRight_exp[i]];
                    iEnvelopeRight_exp[i] = iEnvelopeLeft_exp[i] - iEnvelopeRight_exp[i];

                }
                else        /*  1/(1+2^m) == 2^-m ;  for m >= 10  */
                {
                    iEnvelopeRight_exp[i] = iEnvelopeLeft_exp[i];
                    iEnvelopeLeft_exp[i] = 0;
                }
            }

            iEnvelopeLeft_man[i]  = iEnvelopeRight_man[i];
        }
    }
    else
    {
        for (i = 0; i < hFrameData1->nScaleFactors; i++)
        {
            /*  iEnvelope[i] always positive  7 bits max */
            tempRight = iEnvelopeRight_man[i];
            tempLeft  = iEnvelopeLeft_man[i];

            iEnvelopeLeft_exp[i] = (tempLeft >> 1) + 7;
            if (tempLeft & 0x1)   /*  odd */
            {
                iEnvelopeLeft_man[i] = Qfmt(1.41421356237310F);
            }
            else
            {
                iEnvelopeLeft_man[i] = Qfmt(1.000F);
            }

            iEnvelopeRight_exp[i] = (tempRight >> 1) - 12;
            if (tempRight & 0x1)   /*  odd */
            {
                if (iEnvelopeRight_exp[i] > 0)
                {
                    iEnvelopeRight_man[i] = Qfmt(1.41421356237310F);
                }
                else
                {
                    iEnvelopeRight_man[i] = Qfmt(0.7071067811865F);
                }
            }
            else
            {
                iEnvelopeRight_man[i] = Qfmt(1.000F);
            }

            if (iEnvelopeRight_man[i] == Qfmt(1.000F))
            {

                /*
                 *  iEnvelopeRight[i] = tempLeft / (1 + tempRight);
                 *  iEnvelopeLeft[i]  = tempRight * iEnvelopeRight[i];
                 *
                 *
                 *   iEnvelopeRight[i] = k*2^n/(1+2^m) =  k*2^(n-m)/(1 + 2^-m);
                 *   where k = 1 or sqrt(2)
                 */
                if (iEnvelopeRight_exp[i] >= 0)
                {
                    if (iEnvelopeRight_exp[i] < 11)
                    {
                        iEnvelopeRight_man[i] = one_over_one_plus_two_to_n[ iEnvelopeRight_exp[i]];
                    }
                    else        /*  1/(1+2^-m) == 1 - 2^-m ;  for m >= 10  */
                    {
                        iEnvelopeRight_man[i] -= (Qfmt(1.000F) >> iEnvelopeRight_exp[i]);
                    }
                    iEnvelopeRight_exp[i] = iEnvelopeLeft_exp[i] - iEnvelopeRight_exp[i];

                }
                else
                {
                    if (iEnvelopeRight_exp[i] > -11)
                    {
                        iEnvelopeRight_man[i] -= one_over_one_plus_two_to_n[ -iEnvelopeRight_exp[i]];
                        iEnvelopeRight_exp[i] = iEnvelopeLeft_exp[i] - iEnvelopeRight_exp[i];
                    }
                    else        /*  1/(1+2^m) == 2^-m ;  for m >= 10  */
                    {
                        iEnvelopeRight_exp[i] = iEnvelopeLeft_exp[i];
                        iEnvelopeLeft_exp[i]  = 0;
                    }
                }

                /*
                 *  apply "k" factor 1 or sqrt(2)
                 *
                 *   (2^m)*2*k*2^n/(1+2^m) =  k*2^(n+1)/(1 + 2^-m);
                 *
                 */
                if (iEnvelopeLeft_man[i] != Qfmt(1.000F))
                {
                    iEnvelopeRight_man[i] = fxp_mul32_Q30(iEnvelopeLeft_man[i], iEnvelopeRight_man[i]);
                }

                iEnvelopeLeft_man[i]  = iEnvelopeRight_man[i];

            }
            else
            {

                /*
                *  iEnvelopeRight[i] = tempLeft / (1 + tempRight);
                *  iEnvelopeLeft[i]  = tempRight * iEnvelopeRight[i];
                *
                *
                *   iEnvelopeRight[i] = k*2^n/(1+q2^m) =  k*2^(n-m)/(1 + q2^-m);
                *   where k = 1 or sqrt(2)
                *   and q = sqrt(2)
                    */
                if (iEnvelopeRight_exp[i] >= 0)
                {
                    if (iEnvelopeRight_exp[i] < 12)
                    {
                        iEnvelopeRight_man[i] = one_over_one_plus_sq_2_by_two_to_n[ iEnvelopeRight_exp[i]];
                    }
                    else        /*  1/(1+2^-m) == 1 - 2^-m ;  for m >= 11  */
                    {
                        iEnvelopeRight_man[i] = Qfmt(1.000F) - (Qfmt(1.000F) >> iEnvelopeRight_exp[i]);
                    }
                }
                else
                {
                    if (iEnvelopeRight_exp[i] > -12)
                    {
                        iEnvelopeRight_man[i] = Qfmt(1.000F) - one_over_one_plus_sq_2_by_two_to_n[ -iEnvelopeRight_exp[i]];
                    }
                    else        /*  1/(1+2^m) == 2^-m ;  for m >= 11  */
                    {
                        iEnvelopeRight_man[i] = Qfmt(1.000F);
                        iEnvelopeRight_exp[i] = 0;
                    }
                }

                iEnvelopeRight_exp[i] = iEnvelopeLeft_exp[i] - iEnvelopeRight_exp[i];

                /*
                *  apply "k" factor 1 or sqrt(2)
                *
                *   Right ==    k*2^(n-m)/(1 + q2^-m)
                *   Left  == (q2^m)*k*2^n/(1 + q2^m) =  qk*2^n/(1 + q2^-m);
                */
                if (iEnvelopeLeft_man[i] != Qfmt(1.000F))
                {
                    /*
                    *   k/(1 + q2^-m);
                        */
                    tmp = iEnvelopeRight_man[i];
                    iEnvelopeRight_man[i] = fxp_mul32_Q30(iEnvelopeLeft_man[i], iEnvelopeRight_man[i]);
                    iEnvelopeLeft_man[i] = tmp;
                    iEnvelopeLeft_exp[i] += 1;      /* extra one due to sqrt(2)^2 */
                }
                else
                {
                    iEnvelopeLeft_man[i]  = fxp_mul32_Q30(iEnvelopeRight_man[i], Qfmt(1.41421356237310F));
                }

            }       /*  end of     if (iEnvelopeRight_man[i] == Qfmt( 1.000F) )  */
        }      /* end of for loop */
    }     /*  end  if (hFrameData2->ampRes) */


    for (i = 0; i < hFrameData1->nNoiseFactors; i++)
    {

        noiseFloorLeft_exp[i]  = NOISE_FLOOR_OFFSET_PLUS_1 - noiseFloorLeft_man[i];
        noiseFloorRight_exp[i] = noiseFloorRight_man[i] - SBR_ENERGY_PAN_OFFSET_INT;


        /*
         *  noiseFloorRight[i] = tempLeft / (1.0f + tempRight);
         *  noiseFloorLeft[i]  = tempRight*noiseFloorRight[i];
         *
         *
         *   noiseFloorRight[i] = 2^n/(1+2^m) =  2^(n-m)/(1 + 2^-m);
         */
        if (noiseFloorRight_exp[i] >= 0)
        {
            if (noiseFloorRight_exp[i] < 11)
            {
                noiseFloorRight_man[i] = one_over_one_plus_two_to_n[ noiseFloorRight_exp[i]];
            }
            else        /*  1/(1+2^-m) == 1 - 2^-m ;  for m >= 10  */
            {
                noiseFloorRight_man[i] = Qfmt(1.000F) - (Qfmt(1.000F) >> noiseFloorRight_exp[i]);
            }
        }
        else
        {
            if (noiseFloorRight_exp[i] > -11)
            {
                noiseFloorRight_man[i] = Qfmt(1.000F) - one_over_one_plus_two_to_n[ -noiseFloorRight_exp[i]];
            }
            else        /*  1/(1+2^m) == 2^-m ;  for m >= 10  */
            {
                noiseFloorRight_man[i] = Qfmt(1.000F);
                noiseFloorRight_exp[i] = 0;
            }
        }

        noiseFloorRight_exp[i] = noiseFloorLeft_exp[i] - noiseFloorRight_exp[i];

        /*
         *   (2^m)*2^n/(1+2^m) =  2^n/(1 + 2^-m);
         */

        noiseFloorLeft_man[i] = noiseFloorRight_man[i];
        noiseFloorLeft_exp[i] = noiseFloorLeft_exp[i];

    }
}

#endif

