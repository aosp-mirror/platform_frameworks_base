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

 Filename: ps_decorrelate.c

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


  Delay is introduced to compensate QMF bands not passed through Hybrid
  Analysis

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
#include    "ps_decorrelate.h"
#include    "aac_mem_funcs.h"
#include    "ps_all_pass_filter_coeff.h"
#include    "ps_pwr_transient_detection.h"
#include    "ps_all_pass_fract_delay_filter.h"
#include    "fxp_mul32.h"

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
void ps_decorrelate(STRUCT_PS_DEC *h_ps_dec,
                    Int32 *rIntBufferLeft,
                    Int32 *iIntBufferLeft,
                    Int32 *rIntBufferRight,
                    Int32 *iIntBufferRight,
                    Int32 scratch_mem[])
{
    Int32 sb;
    Int32 maxsb;
    Int32 gr;
    Int32 sb_delay;
    Int32 bin;




    Int32 *aLeftReal;
    Int32 *aLeftImag;
    Int32 *aRightReal;
    Int32 *aRightImag;

    Int32 *aTransRatio = scratch_mem;   /* use  NO_BINS == 20 */


    Int32 ***pppRealDelayRBufferSer;
    Int32 ***pppImagDelayRBufferSer;

    Int32 **ppRealDelayBuffer;
    Int32 **ppImagDelayBuffer;

    const Int32(*ppFractDelayPhaseFactorSer)[3];
    /*
     *  Power transient estimation and detection
     */


    ps_pwr_transient_detection(h_ps_dec,
                               rIntBufferLeft,
                               iIntBufferLeft,
                               aTransRatio);


    aLeftReal = h_ps_dec->mHybridRealLeft;
    aLeftImag = h_ps_dec->mHybridImagLeft;
    aRightReal = h_ps_dec->mHybridRealRight;
    aRightImag = h_ps_dec->mHybridImagRight;

    pppRealDelayRBufferSer = h_ps_dec->aaaRealDelayRBufferSerSubQmf;
    pppImagDelayRBufferSer = h_ps_dec->aaaImagDelayRBufferSerSubQmf;

    ppRealDelayBuffer = h_ps_dec->aaRealDelayBufferSubQmf;
    ppImagDelayBuffer = h_ps_dec->aaImagDelayBufferSubQmf;



    ppFractDelayPhaseFactorSer = aaFractDelayPhaseFactorSerSubQmf;


    /*
     *   NO_IID_GROUPS (SUBQMF_GROUPS (12) + QMF_GROUPS (10)) == 22
     */

    for (gr = 0; gr < SUBQMF_GROUPS; gr++)      /*  0 to 9 */
    {
        Int32 rIn;
        Int32 iIn;
        Int32 *pt_rTmp;
        Int32 *pt_iTmp;
        Int32 rTmp;
        Int32 cmplx;
        Int32 tmp1, tmp2;

        /* sb = subQMF/QMF subband */

        sb = groupBorders[gr];

        /*
         *  For lower subbands
         *  Apply all-pass filtering
         *
         */
        pt_rTmp = &ppRealDelayBuffer[sb][h_ps_dec->delayBufIndex];
        pt_iTmp = &ppImagDelayBuffer[sb][h_ps_dec->delayBufIndex];

        tmp1 = aLeftReal[sb];
        tmp2 = aLeftImag[sb];
        rIn = *pt_rTmp >> 1;
        iIn = *pt_iTmp >> 1;


        *pt_rTmp = tmp1;
        *pt_iTmp = tmp2;

        /*
         *  Fractional delay vector
         *
         *  phi_fract(k) = exp(-j*pi*q_phi*f_center(k))       0<= k <= SUBQMF_GROUPS
         *
         *  q_phi = 0.39
         *  f_center(k) frequency vector
         */

        cmplx =  aFractDelayPhaseFactorSubQmf[sb];

        aRightReal[sb]  = cmplx_mul32_by_16(rIn, -iIn, cmplx);
        aRightImag[sb]  = cmplx_mul32_by_16(iIn,  rIn, cmplx);

        ps_all_pass_fract_delay_filter_type_I(h_ps_dec->aDelayRBufIndexSer,
                                              sb,
                                              ppFractDelayPhaseFactorSer[sb],
                                              pppRealDelayRBufferSer,
                                              pppImagDelayRBufferSer,
                                              &aRightReal[sb],
                                              &aRightImag[sb]);

        bin = bins2groupMap[gr];
        rTmp = aTransRatio[bin];

        if (rTmp != 0x7FFFFFFF)
        {
            aRightReal[sb] = fxp_mul32_Q31(rTmp, aRightReal[sb]) << 1;
            aRightImag[sb] = fxp_mul32_Q31(rTmp, aRightImag[sb]) << 1;
        }


    } /* gr */

    aLeftReal = rIntBufferLeft;
    aLeftImag = iIntBufferLeft;
    aRightReal = rIntBufferRight;
    aRightImag = iIntBufferRight;

    pppRealDelayRBufferSer = h_ps_dec->aaaRealDelayRBufferSerQmf;
    pppImagDelayRBufferSer = h_ps_dec->aaaImagDelayRBufferSerQmf;

    ppRealDelayBuffer = h_ps_dec->aaRealDelayBufferQmf;
    ppImagDelayBuffer = h_ps_dec->aaImagDelayBufferQmf;



    ppFractDelayPhaseFactorSer = aaFractDelayPhaseFactorSerQmf;


    for (gr = SUBQMF_GROUPS; gr < NO_BINS; gr++)     /* 10 to 20 */
    {

        maxsb = min(h_ps_dec->usb, groupBorders[gr+1]);

        /* sb = subQMF/QMF subband */

        for (sb = groupBorders[gr]; sb < maxsb; sb++)
        {

            Int32 rIn, iIn;
            Int32 *pt_rTmp, *pt_iTmp;
            Int32 cmplx;
            Int32 tmp1, tmp2;
            Int32 rTmp;


            sb_delay = sb - NO_QMF_CHANNELS_IN_HYBRID;  /* NO_QMF_CHANNELS_IN_HYBRID == 3 */

            /*
             *  For lower subbands
             *  Apply all-pass filtering
             *
             */
            pt_rTmp = &ppRealDelayBuffer[sb_delay][h_ps_dec->delayBufIndex];
            pt_iTmp = &ppImagDelayBuffer[sb_delay][h_ps_dec->delayBufIndex];

            rIn = *pt_rTmp >> 1;
            iIn = *pt_iTmp >> 1;

            tmp1 = aLeftReal[sb];
            tmp2 = aLeftImag[sb];
            *pt_rTmp = tmp1;
            *pt_iTmp = tmp2;

            /*
             *  Fractional delay vector
             *
             *  phi_fract(k) = exp(-j*pi*q_phi*f_center(k))       0<= k <= SUBQMF_GROUPS
             *
             *  q_phi = 0.39
             *  f_center(k) frequency vector
             */

            cmplx =  aFractDelayPhaseFactor[sb_delay];
            aRightReal[sb] = cmplx_mul32_by_16(rIn, -iIn, cmplx);
            aRightImag[sb] = cmplx_mul32_by_16(iIn,  rIn, cmplx);

            ps_all_pass_fract_delay_filter_type_II(h_ps_dec->aDelayRBufIndexSer,
                                                   sb_delay,
                                                   ppFractDelayPhaseFactorSer[sb_delay],
                                                   pppRealDelayRBufferSer,
                                                   pppImagDelayRBufferSer,
                                                   &aRightReal[sb],
                                                   &aRightImag[sb],
                                                   sb);

            rTmp = aTransRatio[gr-2];
            if (rTmp != 0x7FFFFFFF)
            {
                aRightReal[sb] = fxp_mul32_Q31(rTmp, aRightReal[sb]) << 1;
                aRightImag[sb] = fxp_mul32_Q31(rTmp, aRightImag[sb]) << 1;
            }


        } /* sb */

    }


    maxsb = min(h_ps_dec->usb, 35);  /*  35 == groupBorders[NO_BINS + 1] */

    /* sb = subQMF/QMF subband */
    {
        Int32 factor = aTransRatio[NO_BINS-2];

        for (sb = 23; sb < maxsb; sb++)    /*  23 == groupBorders[NO_BINS] */
        {

            Int32  tmp, tmp2;
            Int32 *pt_rTmp, *pt_iTmp;

            sb_delay = sb - NO_QMF_CHANNELS_IN_HYBRID;  /*  == 3 */

            /*
             *  For the Upper Bands apply delay only
             *                          -D(k)
             *  Apply Delay   H_k(z) = z         , D(k) == 1 or 14
             *
             */
            Int32 k = sb - NO_ALLPASS_CHANNELS;  /* == 23 */


            pt_rTmp = &ppRealDelayBuffer[sb_delay][h_ps_dec->aDelayBufIndex[ k]];
            pt_iTmp = &ppImagDelayBuffer[sb_delay][h_ps_dec->aDelayBufIndex[ k]];

            if (++h_ps_dec->aDelayBufIndex[ k] >= LONG_DELAY)     /* == 14 */
            {
                h_ps_dec->aDelayBufIndex[ k] = 0;
            }


            tmp  = *pt_rTmp;
            tmp2 = *pt_iTmp;

            if (aTransRatio[NO_BINS-2] < 0x7FFFFFFF)
            {
                aRightReal[sb] = fxp_mul32_Q31(factor, tmp) << 1;
                aRightImag[sb] = fxp_mul32_Q31(factor, tmp2) << 1;
            }
            else
            {
                aRightReal[sb] = tmp;
                aRightImag[sb] = tmp2;
            }


            tmp  = aLeftReal[sb];
            tmp2 = aLeftImag[sb];
            *pt_rTmp = tmp;
            *pt_iTmp = tmp2;


        } /* sb */
    }


    maxsb = min(h_ps_dec->usb, 64);     /*  64 == groupBorders[NO_BINS+2] */

    /* sb = subQMF/QMF subband */

    {

        for (sb = 35; sb < maxsb; sb++)    /*  35 == groupBorders[NO_BINS+1] */
        {

            Int32 *pt_rTmp, *pt_iTmp;

            sb_delay = sb - NO_QMF_CHANNELS_IN_HYBRID;  /*  == 3 */

            /*
             *  For the Upper Bands apply delay only
             *                          -D(k)
             *  Apply Delay   H_k(z) = z         , D(k) == 1 or 14
             *
             */

            pt_rTmp = &ppRealDelayBuffer[sb_delay][0];
            pt_iTmp = &ppImagDelayBuffer[sb_delay][0];

            aRightReal[sb] = *pt_rTmp;
            aRightImag[sb] = *pt_iTmp;


            if (aTransRatio[NO_BINS-1] < 0x7FFFFFFF)
            {
                aRightReal[sb] = fxp_mul32_Q31(aTransRatio[NO_BINS-1], aRightReal[sb]) << 1;
                aRightImag[sb] = fxp_mul32_Q31(aTransRatio[NO_BINS-1], aRightImag[sb]) << 1;
            }

            *pt_rTmp = aLeftReal[sb];
            *pt_iTmp = aLeftImag[sb];


        } /* sb */
    }


    if (++h_ps_dec->delayBufIndex >= DELAY_ALLPASS)
    {
        h_ps_dec->delayBufIndex = 0;
    }

    if (++h_ps_dec->aDelayRBufIndexSer[0] >= 3)
    {
        h_ps_dec->aDelayRBufIndexSer[0] = 0;
    }
    if (++h_ps_dec->aDelayRBufIndexSer[1] >= 4)
    {
        h_ps_dec->aDelayRBufIndexSer[1] = 0;
    }
    if (++h_ps_dec->aDelayRBufIndexSer[2] >= 5)
    {
        h_ps_dec->aDelayRBufIndexSer[2] = 0;
    }


} /* END deCorrelate */
#endif


#endif

