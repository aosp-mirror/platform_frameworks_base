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

 Filename: ps_hybrid_analysis.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

        Does Hybrid analysis

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

#include    "s_hybrid.h"
#include    "aac_mem_funcs.h"
#include    "ps_fft_rx8.h"
#include    "ps_channel_filtering.h"
#include    "pv_audio_type_defs.h"
#include    "fxp_mul32.h"
/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#define R_SHIFT     29
#define Q29_fmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))

#define Qfmt31(a)   (Int32)(-a*((Int32)1<<31)  + (a>=0?0.5F:-0.5F))

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


void two_ch_filtering(const Int32 *pQmf_r,
                      const Int32 *pQmf_i,
                      Int32 *mHybrid_r,
                      Int32 *mHybrid_i)
{

    Int32 cum0;
    Int32 cum1;
    Int32 cum2;
    Int32 tmp1;
    Int32 tmp2;

    tmp1 = pQmf_r[ 1] + pQmf_r[11];
    tmp2 = pQmf_i[ 1] + pQmf_i[11];
    cum1 =   fxp_mul32_Q31(Qfmt31(0.03798975052098f), tmp1);
    cum2 =   fxp_mul32_Q31(Qfmt31(0.03798975052098f), tmp2);
    tmp1 = pQmf_r[ 3] + pQmf_r[ 9];
    tmp2 = pQmf_i[ 3] + pQmf_i[ 9];
    cum1 =   fxp_msu32_Q31(cum1, Qfmt31(0.14586278335076f), tmp1);
    cum2 =   fxp_msu32_Q31(cum2, Qfmt31(0.14586278335076f), tmp2);
    tmp1 = pQmf_r[ 5] + pQmf_r[ 7];
    tmp2 = pQmf_i[ 5] + pQmf_i[ 7];
    cum1 =   fxp_mac32_Q31(cum1, Qfmt31(0.61193261090336f), tmp1);
    cum2 =   fxp_mac32_Q31(cum2, Qfmt31(0.61193261090336f), tmp2);

    cum0 = pQmf_r[HYBRID_FILTER_DELAY] >> 1;  /* HYBRID_FILTER_DELAY == 6 */

    mHybrid_r[0] = (cum0 + cum1);
    mHybrid_r[1] = (cum0 - cum1);

    cum0 = pQmf_i[HYBRID_FILTER_DELAY] >> 1;  /* HYBRID_FILTER_DELAY == 6 */

    mHybrid_i[0] = (cum0 + cum2);
    mHybrid_i[1] = (cum0 - cum2);

}





/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/


void eight_ch_filtering(const Int32 *pQmfReal,
                        const Int32 *pQmfImag,
                        Int32 *mHybridReal,
                        Int32 *mHybridImag,
                        Int32 scratch_mem[])

{

    Int32 real;
    Int32 imag;
    Int32 tmp1;
    Int32 tmp2;

    real  = fxp_mul32_Q29(Q29_fmt(-0.06989827306334f), pQmfReal[ 4]);

    real  = fxp_mac32_Q31(real, Qfmt31(0.01055120626280f), pQmfReal[12]);
    imag  = fxp_mul32_Q29(Q29_fmt(-0.06989827306334f), pQmfImag[ 4]);

    imag  = fxp_mac32_Q31(imag, Qfmt31(0.01055120626280f), pQmfImag[12]);

    mHybridReal[2] = (imag - real);
    mHybridImag[2] = -(imag + real);

    real  = fxp_mul32_Q29(Q29_fmt(-0.07266113929591f), pQmfReal[ 3]);

    real  = fxp_mac32_Q31(real, Qfmt31(0.04540841899650f), pQmfReal[11]);
    imag  = fxp_mul32_Q29(Q29_fmt(-0.07266113929591f), pQmfImag[ 3]);

    imag  = fxp_mac32_Q31(imag, Qfmt31(0.04540841899650f), pQmfImag[11]);

    tmp1           =  fxp_mul32_Q29(Q29_fmt(-0.38268343236509f), real);
    mHybridReal[3] =  fxp_mac32_Q29(Q29_fmt(0.92387953251129f), imag, tmp1);
    tmp2           =  fxp_mul32_Q29(Q29_fmt(-0.92387953251129f), real);
    mHybridImag[3] =  fxp_mac32_Q29(Q29_fmt(-0.38268343236509f), imag, tmp2);


    mHybridImag[4] = fxp_mul32_Q31(Qfmt31(0.09093731860946f), (pQmfReal[ 2] - pQmfReal[10]));
    mHybridReal[4] = fxp_mul32_Q31(Qfmt31(0.09093731860946f), (pQmfImag[10] - pQmfImag[ 2]));


    real  = fxp_mul32_Q29(Q29_fmt(-0.02270420949825f), pQmfReal[ 1]);

    real  = fxp_mac32_Q31(real, Qfmt31(0.14532227859182f), pQmfReal[ 9]);
    imag  = fxp_mul32_Q29(Q29_fmt(-0.02270420949825f), pQmfImag[ 1]);

    imag  = fxp_mac32_Q31(imag, Qfmt31(0.14532227859182f), pQmfImag[ 9]);

    tmp1           =  fxp_mul32_Q29(Q29_fmt(0.92387953251129f), imag);

    mHybridReal[5] =  fxp_mac32_Q31(tmp1, Qfmt31(0.76536686473018f), real);
    tmp2           =  fxp_mul32_Q29(Q29_fmt(-0.92387953251129f), real);

    mHybridImag[5] =  fxp_mac32_Q31(tmp2, Qfmt31(0.76536686473018f), imag);

    real  = fxp_mul32_Q29(Q29_fmt(-0.00527560313140f), pQmfReal[ 0]);

    real  = fxp_mac32_Q31(real, Qfmt31(0.13979654612668f), pQmfReal[ 8]);
    imag  = fxp_mul32_Q29(Q29_fmt(-0.00527560313140f), pQmfImag[ 0]);

    imag  = fxp_mac32_Q31(imag, Qfmt31(0.13979654612668f), pQmfImag[ 8]);

    mHybridReal[6] = (imag + real);
    mHybridImag[6] = (imag - real);


    tmp1            =  fxp_mul32_Q31(Qfmt31(0.21791935610828f), pQmfReal[ 7]);
    mHybridReal[7]  =  fxp_mac32_Q31(tmp1, Qfmt31(0.09026515280366f), pQmfImag[ 7]);

    tmp2            =  fxp_mul32_Q29(Q29_fmt(-0.04513257640183f), pQmfReal[ 7]);

    mHybridImag[7]  =  fxp_mac32_Q31(tmp2, Qfmt31(0.21791935610828f), pQmfImag[ 7]);

    mHybridReal[0] = pQmfReal[HYBRID_FILTER_DELAY] >> 3;
    mHybridImag[0] = pQmfImag[HYBRID_FILTER_DELAY] >> 3;

    tmp1           =  fxp_mul32_Q29(Q29_fmt(-0.04513257640183f), pQmfImag[ 5]);

    mHybridReal[1] =  fxp_mac32_Q31(tmp1, Qfmt31(0.21791935610828f), pQmfReal[ 5]);


    tmp2            =  fxp_mul32_Q31(Qfmt31(0.21791935610828f), pQmfImag[ 5]);
    mHybridImag[1]  =  fxp_mac32_Q31(tmp2, Qfmt31(0.09026515280366f), pQmfReal[ 5]);

    /*
     *  8*ifft
     */

    ps_fft_rx8(mHybridReal, mHybridImag, scratch_mem);

}


#endif


#endif

