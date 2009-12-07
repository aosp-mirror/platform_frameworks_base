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

 Filename: ps_pwr_transient_detection.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

  Decorrelation
  Decorrelation is achieved by means of all-pass filtering and delaying
  Sub-band samples s_k(n) are converted into de-correlated sub-bands samples
  d_k(n). k index for frequency, n time index


     _______                                              ________
    |       |                                  _______   |        |
  ->|Hybrid | LF ----                         |       |->| Hybrid |-->
    | Anal. |        |                        |       |  | Synth  |   QMF -> L
     -------         o----------------------->|       |   --------    Synth
QMF                  |                s_k(n)  |Stereo |-------------->
Anal.              -------------------------->|       |
     _______       | |                        |       |   ________
    |       | HF --o |   -----------          |Process|  |        |
  ->| Delay |      |  ->|           |-------->|       |->| Hybrid |-->
     -------       |    |decorrelate| d_k(n)  |       |  | Synth  |   QMF -> R
                   ---->|           |-------->|       |   --------    Synth
                         -----------          |_______|-------------->


  To handle transients and other fast time-envelopes, the output of the all
  pass filters has to be attenuated at those signals.


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

#include    "pv_audio_type_defs.h"
#include    "s_ps_dec.h"
#include    "aac_mem_funcs.h"
#include    "ps_all_pass_filter_coeff.h"
#include    "ps_pwr_transient_detection.h"

#include    "fxp_mul32.h"
#include    "pv_div.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/

#ifndef min
#define min(a, b) ((a) < (b) ? (a) : (b))
#endif


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/

#define R_SHIFT     29
#define Q29_fmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))

#define Qfmt31(a)   (Int32)(-a*((Int32)1<<31) - 1 + (a>=0?0.5F:-0.5F))

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


void ps_pwr_transient_detection(STRUCT_PS_DEC *h_ps_dec,
                                Int32 *rIntBufferLeft,
                                Int32 *iIntBufferLeft,
                                Int32 aTransRatio[])
{

    Int32 sb;
    Int32 maxsb;
    Int32 gr;
    Int32 bin;



    Int32 *aLeftReal;
    Int32 *aLeftImag;
    Int32   temp_r;
    Int32   temp_i;
    Int32   accu;
    Int32 *aPower = aTransRatio;
    Quotient result;

    Int32 nrg;
    Int32 *ptr_aPrevNrg;
    Int32 peakDiff;
    Int32 *ptr_PrevPeakDiff;


    aLeftReal = rIntBufferLeft;
    aLeftImag = iIntBufferLeft;

    /*
     *  Input Power Matrix
     *                            2
     *  Power(i,n) = SUM | s_k(n)|
     *                i
     */

    for (gr = SUBQMF_GROUPS; gr < NO_IID_GROUPS; gr++) /* 10 to 22  */
    {
        maxsb = min(h_ps_dec->usb, groupBorders[ gr+1]);

        accu = 0;

        for (sb = groupBorders[gr]; sb < maxsb; sb++)
        {

            temp_r = aLeftReal[sb];
            temp_i = aLeftImag[sb];
            accu =  fxp_mac32_Q31(accu, temp_r, temp_r);
            accu =  fxp_mac32_Q31(accu, temp_i, temp_i);

        } /* sb */
        aPower[gr - 2] = accu >> 1;
    } /* gr */

    aLeftReal = h_ps_dec->mHybridRealLeft;
    aLeftImag = h_ps_dec->mHybridImagLeft;


    temp_r = aLeftReal[0];
    temp_i = aLeftImag[0];
    accu   = fxp_mul32_Q31(temp_r, temp_r);
    accu  = fxp_mac32_Q31(accu, temp_i, temp_i);
    temp_r = aLeftReal[5];
    temp_i = aLeftImag[5];
    accu   = fxp_mac32_Q31(accu, temp_r, temp_r);
    aPower[0]  = fxp_mac32_Q31(accu, temp_i, temp_i) >> 1;

    temp_r = aLeftReal[1];
    temp_i = aLeftImag[1];
    accu   = fxp_mul32_Q31(temp_r, temp_r);
    accu  = fxp_mac32_Q31(accu, temp_i, temp_i);
    temp_r = aLeftReal[4];
    temp_i = aLeftImag[4];
    accu   = fxp_mac32_Q31(accu, temp_r, temp_r);
    aPower[1]  = fxp_mac32_Q31(accu, temp_i, temp_i) >> 1;

    temp_r = aLeftReal[2];
    temp_i = aLeftImag[2];
    accu   = fxp_mul32_Q31(temp_r, temp_r);
    aPower[2]  = fxp_mac32_Q31(accu, temp_i, temp_i) >> 1;

    temp_r = aLeftReal[3];
    temp_i = aLeftImag[3];
    accu   = fxp_mul32_Q31(temp_r, temp_r);
    aPower[3]  = fxp_mac32_Q31(accu, temp_i, temp_i) >> 1;



    temp_r = aLeftReal[6];
    temp_i = aLeftImag[6];
    accu   = fxp_mul32_Q31(temp_r, temp_r);
    aPower[5]  = fxp_mac32_Q31(accu, temp_i, temp_i) >> 1;

    temp_r = aLeftReal[7];
    temp_i = aLeftImag[7];
    accu   = fxp_mul32_Q31(temp_r, temp_r);
    aPower[4]  = fxp_mac32_Q31(accu, temp_i, temp_i) >> 1;

    temp_r = aLeftReal[8];
    temp_i = aLeftImag[8];
    accu   = fxp_mul32_Q31(temp_r, temp_r);
    aPower[6]  = fxp_mac32_Q31(accu, temp_i, temp_i) >> 1;

    temp_r = aLeftReal[9];
    temp_i = aLeftImag[9];
    accu   = fxp_mul32_Q31(temp_r, temp_r);
    aPower[7]  = fxp_mac32_Q31(accu, temp_i, temp_i) >> 1;


    /*
     *  Power transient detection
     */

    ptr_aPrevNrg = h_ps_dec->aPrevNrg;

    ptr_PrevPeakDiff = h_ps_dec->aPrevPeakDiff;

    for (bin = 0; bin < NO_BINS; bin++) /* NO_BINS = 20  */
    {

        peakDiff  = *ptr_PrevPeakDiff;


        /* PEAK_DECAY_FACTOR  0.765928338364649f @ 48 KHz  for Fs > 32 Khz */
        accu = h_ps_dec->aPeakDecayFast[bin];
        peakDiff -= peakDiff >> 2;

        accu  = fxp_mul32_Q31(accu, Qfmt31(0.765928338364649f)) << 1;

        if (accu < *aPower)
        {
            accu = *aPower;
        }
        else
        {
            peakDiff += ((accu - *aPower) >> 2);
        }

        h_ps_dec->aPeakDecayFast[bin] = accu;

        *(ptr_PrevPeakDiff++) = peakDiff;

        nrg =   *ptr_aPrevNrg + ((*aPower - *ptr_aPrevNrg) >> 2);

        *(ptr_aPrevNrg++) = nrg;

        peakDiff += peakDiff >> 1;         /* transient impact factor == 1.5 */

        if (peakDiff <= nrg)
        {
            *(aPower++) = 0x7FFFFFFF;    /* in Q31  */
        }
        else
        {
            pv_div(nrg, peakDiff, &result);
            *(aPower++) = (result.quotient >> (result.shift_factor)) << 1;    /* in Q31  */
        }

    } /* bin */

}


#endif

#endif
