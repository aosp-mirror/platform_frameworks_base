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

 Filename: sbr_create_limiter_bands.c

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


#include    "sbr_create_limiter_bands.h"
#include    "shellsort.h"
#include    "s_patch.h"
#include    "pv_log2.h"

#include "fxp_mul32.h"

#define R_SHIFT     29
#define Q_fmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))


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

void sbr_create_limiter_bands(Int32 limSbc[][13],
                              Int32 *gateMode,
                              Int   *freqTable,
                              struct PATCH Patch,
                              const Int32 noBands)
{
    Int32 i;
    Int32 j;
    Int32 k;
    Int isPatchBorder[2];
    Int32 patchBorders[MAX_NUM_PATCHES + 1];
    Int32 workLimiterBandTable[32 + MAX_NUM_PATCHES + 1];

    Int32 nOctaves;
    const Int32 limiterBandsPerOctave[4] =
        {Q_fmt(0.0F), Q_fmt(1.2F),
         Q_fmt(2.0F), Q_fmt(3.0F)
        };

    Int32 tmp_q1;

    Int32 noPatches = Patch.noOfPatches;
    Int32 lowSubband = freqTable[0];
    Int32 highSubband = freqTable[noBands];


    for (i = 0; i < noPatches; i++)
    {
        patchBorders[i] = Patch.targetStartBand[i] - lowSubband;
    }
    patchBorders[i] = highSubband - lowSubband;

    /* First band: 1 limiter band. */
    limSbc[0][0] = freqTable[0] - lowSubband;
    limSbc[0][1] = freqTable[noBands] - lowSubband;
    gateMode[0] = 1;

    /* Next three bands: 1.2, 2, 3 limiter bands/octave plus bandborders at patchborders. */
    for (i = 1; i < 4; i++)
    {

        for (k = 0; k <= noBands; k++)
        {
            workLimiterBandTable[k] = freqTable[k] - lowSubband;
        }

        for (k = 1; k < noPatches; k++)
        {
            workLimiterBandTable[noBands+k] = patchBorders[k];
        }

        gateMode[i] = noBands + noPatches - 1;
        shellsort(workLimiterBandTable, gateMode[i] + 1);

        for (j = 1; j <= gateMode[i]; j++)
        {
            tmp_q1 = ((workLimiterBandTable[j] + lowSubband) << 20) / (workLimiterBandTable[j-1] + lowSubband);

            nOctaves = pv_log2(tmp_q1);

            tmp_q1 = fxp_mul32_Q20(nOctaves, limiterBandsPerOctave[i]);
            if (tmp_q1 < Q_fmt(0.49))
            {
                if (workLimiterBandTable[j] == workLimiterBandTable[j-1])
                {
                    workLimiterBandTable[j] = highSubband;
                    shellsort(workLimiterBandTable, gateMode[i] + 1);
                    gateMode[i]--;
                    j--;
                    continue;
                }

                isPatchBorder[0] = isPatchBorder[1] = 0;

                for (k = 0; k <= noPatches; k++)
                {
                    if (workLimiterBandTable[j-1] == patchBorders[k])
                    {
                        isPatchBorder[0] = 1;
                        break;
                    }
                }

                for (k = 0; k <= noPatches; k++)
                {
                    if (workLimiterBandTable[j] == patchBorders[k])
                    {
                        isPatchBorder[1] = 1;
                        break;
                    }
                }

                if (!isPatchBorder[1])
                {
                    workLimiterBandTable[j] = highSubband;
                    shellsort(workLimiterBandTable, gateMode[i] + 1);
                    gateMode[i]--;
                    j--;
                }
                else if (!isPatchBorder[0])
                {
                    workLimiterBandTable[j-1] = highSubband;
                    shellsort(workLimiterBandTable, gateMode[i] + 1);
                    gateMode[i]--;
                    j--;
                }
            }
        }
        for (k = 0; k <= gateMode[i]; k++)
        {
            limSbc[i][k] = workLimiterBandTable[k];
        }
    }
}



#endif

