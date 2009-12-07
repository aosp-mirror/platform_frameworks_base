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

 Filename: sbr_update_freq_scale.c

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


#include    "sbr_update_freq_scale.h"
#include    "shellsort.h"

#include    "pv_pow2.h"
#include    "pv_log2.h"

#include "fxp_mul32.h"
#define R_SHIFT     30
#define Q_fmt(x)    (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))
#define Q28fmt(x)   (Int32)(x*((Int32)1<<28) + (x>=0?0.5F:-0.5F))

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



void sbr_update_freq_scale(Int32 * v_k_master,
                           Int32 *h_num_bands,
                           const Int32 lsbM,
                           const Int32 usb,
                           const Int32 freqScale,
                           const Int32 alterScale,
                           const Int32 channelOffset)
{
    Int32 i;
    Int32 numBands = 0;
    Int32 numBands2;
    Int32 tmp_q1;

    if (freqScale > 0) /*Bark mode*/
    {
        Int32 reg;
        Int32 regions;
        Int32 b_p_o;
        Int32 k[3];
        Int32 d[MAX_SECOND_REGION];
        Int32 d2[MAX_SECOND_REGION];
        Int32 w[2] = {Q_fmt(1.0F), Q_fmt(1.0F)};


        k[0] = lsbM;
        k[1] = usb;
        k[2] = usb;

        b_p_o = (freqScale == 1)  ? 12 : 8;
        b_p_o = (freqScale == 2)  ? 10 : b_p_o;

        w[1]  = (alterScale == 0) ? Q_fmt(0.5f) : Q_fmt(0.384615384615386f);

        if (usb > fxp_mul32_Q28(lsbM, Q28fmt(2.2449)))
        {
            regions = 2;
            k[1] = (lsbM << 1);
        }
        else
        {
            regions = 1;
        }

        *h_num_bands = 0;
        for (reg = 0; reg < regions; reg++)
        {
            if (reg == 0)
            {

                tmp_q1 = pv_log2((k[1] << 20) / k[0]);

                tmp_q1 = fxp_mul32_Q15(tmp_q1, b_p_o);
                tmp_q1 = (tmp_q1 + 32) >> 6;

                numBands = tmp_q1 << 1;


                CalcBands(d, k[0], k[1], numBands);                                    /* CalcBands => d   */
                shellsort(d, numBands);                                              /* SortBands sort d */
                cumSum(k[0] - channelOffset,
                       d,
                       numBands,
                       (v_k_master + *h_num_bands));   /* cumsum */

                *h_num_bands += numBands;                                            /* Output nr of bands */
            }
            else
            {
                tmp_q1 = pv_log2((k[reg + 1] << 20) / k[reg]);

                tmp_q1 = fxp_mul32_Q30(tmp_q1, w[reg]);
                tmp_q1 = fxp_mul32_Q15(tmp_q1, b_p_o);
                tmp_q1 = (tmp_q1 + 16) >> 5;

                numBands2 = tmp_q1 << 1;

                CalcBands(d2, k[reg], k[reg+1], numBands2);                            /* CalcBands => d   */
                shellsort(d2, numBands2);                                              /* SortBands sort d */
                if (d[numBands-1] > d2[0])
                {

                    Int32   change = d[numBands-1] - d2[0];
                    /* Limit the change so that the last band cannot get narrower than the first one */
                    if (change > (d2[numBands2-1] - d2[0]) >> 1)
                    {
                        change = (d2[numBands2-1] - d2[0]) >> 1;
                    }

                    d2[0] += change;
                    d2[numBands2-1] -= change;
                    shellsort(d2, numBands2);

                }
                cumSum(k[reg] - channelOffset,
                       d2,
                       numBands2,
                       v_k_master + *h_num_bands);   /* cumsum */

                *h_num_bands += numBands2;                                           /* Output nr of bands */
            }
        }
    }
    else
    {                         /* Linear mode */
        Int32     k2_achived;
        Int32     k2_diff;
        Int32     diff_tot[MAX_OCTAVE + MAX_SECOND_REGION];
        Int32     dk;
        Int32     incr = 0;


        if (alterScale)
        {
            numBands = (usb - lsbM) >> 1;
            dk = 1;
            k2_achived = lsbM + numBands;
        }
        else
        {
            numBands = usb - lsbM;
            if (numBands & 0x1) /* equivalent rounding */
            {
                numBands--;
            }
            dk = 2;
            k2_achived = lsbM + (numBands << 1);
        }

        k2_diff = usb - k2_achived;

        for (i = 0; i < numBands; i++)
        {
            diff_tot[i] = dk;
        }

        if (k2_diff < 0)        /* If linear scale wasn't achived */
        {
            incr = 1;           /* and we got too large SBR area */
            i = 0;
        }

        if (k2_diff > 0)        /* If linear scale wasn't achived */
        {
            incr = -1;            /* and we got too small SBR area */
            i = numBands - 1;
        }

        /* Adjust diff vector to get spec. SBR range */
        while (k2_diff != 0)
        {
            diff_tot[i] -=  incr;
            i += incr;
            k2_diff += incr;
        }

        cumSum(lsbM,
               diff_tot,
               numBands,
               v_k_master); /* cumsum */

        *h_num_bands = numBands;                      /* Output nr of bands */
    }
}


void CalcBands(Int32 * diff,
               Int32 start,
               Int32 stop,
               Int32 num_bands)
{
    Int32 i;
    Int32 previous;
    Int32 current;
    Int32 tmp_q1;


    previous = start;

    for (i = 1; i <= num_bands; i++)
    {
        /*              float temp=(start * pow( (float)stop/start, (float)i/num_bands)); */

        tmp_q1 = pv_log2((stop << 20) / start);

        tmp_q1 = fxp_mul32_Q20(tmp_q1, (i << 27) / num_bands);
        tmp_q1 = pv_pow2(tmp_q1);

        tmp_q1 = fxp_mul32_Q20(tmp_q1, start);

        current = (tmp_q1 + 16) >> 5;

        diff[i-1] = current - previous;
        previous  = current;
    }

}  /* End CalcBands */


void cumSum(Int32 start_value,
            Int32 * diff,
            Int32 length,
            Int32 * start_adress)
{
    Int32 i;
    Int32 *pt_start_adress   = start_adress;
    Int32 *pt_start_adress_1 = start_adress;
    Int32 *pt_diff           = diff;

    if (length > 0)  /*  avoid possible error on loop */
    {
        *(pt_start_adress_1++) = start_value;

        for (i = (length >> 1); i != 0; i--)
        {
            *(pt_start_adress_1++) = *(pt_start_adress++) + *(pt_diff++);
            *(pt_start_adress_1++) = *(pt_start_adress++) + *(pt_diff++);
        }

        if (length&1)
        {
            *(pt_start_adress_1) = *(pt_start_adress) + *(pt_diff);
        }
    }

}   /* End cumSum */


#endif
