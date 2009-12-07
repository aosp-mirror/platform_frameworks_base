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

 Filename: ps_all_pass_fract_delay_filter.c

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

/*
 *  For lower subbands
 *  Apply all-pass filtering
 *
 */


void ps_all_pass_fract_delay_filter_type_I(UInt32 *delayBufIndex,
        Int32 sb_delay,
        const Int32 *ppFractDelayPhaseFactorSer,
        Int32 ***pppRealDelayRBufferSer,
        Int32 ***pppImagDelayRBufferSer,
        Int32 *rIn,
        Int32 *iIn)
{

    Int32 cmplx;
    Int16 rTmp0;
    Int32 rTmp;
    Int32 iTmp;
    Int32 *pt_rTmp;
    Int32 *pt_iTmp;



    /*
     *  All pass filters
     *                         2
     *                        ___  Q_fract(k,m)*z^(-d(m))  -  a(m)*g_decay_slope(k)
     *   z^(-2)*phi_fract(k)* | |  ------------------------------------------------
     *                        m=0  1  - a(m)*g_decay_slope(k)*Q_fract(k,m)*z^(-d(m))
     *
     *
     *    Fractional delay matrix:
     *
     *    Q_fract(k,m) = exp(-j*pi*q(m)*f_center(k))       0<= k <= SUBQMF_GROUPS
     *
     *    Vectors: a(m), q(m), d(m) are constants
     *
     *    m                              m     0       1       2
     *                                 -------------------------------
     *    delay length                 d(m) == 3       4       5      (Fs > 32 KHz)
     *    fractional delay length      q(m) == 0.43    0.75    0.347
     *    filter coefficient           a(m) == 0.65144 0.56472 0.48954
     *
     *             g_decay_slope(k) is given
     */


    Int32 tmp_r;
    Int32 tmp_i;

    pt_rTmp = &pppRealDelayRBufferSer[0][*(delayBufIndex)][sb_delay];
    pt_iTmp = &pppImagDelayRBufferSer[0][*(delayBufIndex++)][sb_delay];

    cmplx  = *(ppFractDelayPhaseFactorSer++);        /* Q_fract(k,m)  */
    tmp_r = *pt_rTmp << 1;
    tmp_i = *pt_iTmp << 1;

    rTmp = cmplx_mul32_by_16(tmp_r, -tmp_i,  cmplx);
    rTmp0  = Qfmt15(0.65143905753106f);
    iTmp = cmplx_mul32_by_16(tmp_i,  tmp_r,  cmplx);


    iTmp     =  fxp_mac32_by_16(-*iIn << 1, rTmp0, iTmp);  /* Q_fract(k,m)*y(n-1) - a(m)*g_decay_slope(k)*x(n) */
    *pt_iTmp =  fxp_mac32_by_16(iTmp << 1, rTmp0, *iIn);   /* y(n) = x(n) + a(m)*g_decay_slope(k)*( Q_fract(k,m)*y(n-1) - a(m)*g_decay_slope(k)*x(n)) */
    *iIn = iTmp;

    rTmp     =  fxp_mac32_by_16(-*rIn << 1, rTmp0, rTmp);
    *pt_rTmp =  fxp_mac32_by_16(rTmp << 1, rTmp0, *rIn);

    *rIn = rTmp;

    pt_rTmp = &pppRealDelayRBufferSer[1][*(delayBufIndex)][sb_delay];
    pt_iTmp = &pppImagDelayRBufferSer[1][*(delayBufIndex++)][sb_delay];



    cmplx  = *(ppFractDelayPhaseFactorSer++);        /* Q_fract(k,m)  */
    tmp_r = *pt_rTmp << 1;
    tmp_i = *pt_iTmp << 1;

    rTmp = cmplx_mul32_by_16(tmp_r, -tmp_i,  cmplx);
    rTmp0  = Qfmt15(0.56471812200776f);
    iTmp = cmplx_mul32_by_16(tmp_i,  tmp_r,  cmplx);


    iTmp     =  fxp_mac32_by_16(-*iIn << 1, rTmp0, iTmp);  /* Q_fract(k,m)*y(n-1) - a(m)*g_decay_slope(k)*x(n) */
    *pt_iTmp =  fxp_mac32_by_16(iTmp << 1, rTmp0, *iIn);   /* y(n) = x(n) + a(m)*g_decay_slope(k)*( Q_fract(k,m)*y(n-1) - a(m)*g_decay_slope(k)*x(n)) */
    *iIn = iTmp;

    rTmp     =  fxp_mac32_by_16(-*rIn << 1, rTmp0, rTmp);
    *pt_rTmp =  fxp_mac32_by_16(rTmp << 1, rTmp0, *rIn);
    *rIn = rTmp;

    pt_rTmp = &pppRealDelayRBufferSer[2][*(delayBufIndex)][sb_delay];
    pt_iTmp = &pppImagDelayRBufferSer[2][*(delayBufIndex)][sb_delay];


    cmplx  = *(ppFractDelayPhaseFactorSer);        /* Q_fract(k,m)  */
    tmp_r = *pt_rTmp << 1;
    tmp_i = *pt_iTmp << 1;

    rTmp = cmplx_mul32_by_16(tmp_r, -tmp_i,  cmplx);
    rTmp0  = Qfmt15(0.97908331911390f);
    iTmp = cmplx_mul32_by_16(tmp_i,  tmp_r,  cmplx);


    iTmp     =  fxp_mac32_by_16(-*iIn, rTmp0, iTmp);    /* Q_fract(k,m)*y(n-1) - a(m)*g_decay_slope(k)*x(n) */
    *pt_iTmp =  fxp_mac32_by_16(iTmp, rTmp0, *iIn);     /* y(n) = x(n) + a(m)*g_decay_slope(k)*( Q_fract(k,m)*y(n-1) - a(m)*g_decay_slope(k)*x(n)) */
    *iIn = iTmp << 2;

    rTmp     =  fxp_mac32_by_16(-*rIn, rTmp0, rTmp);
    *pt_rTmp =  fxp_mac32_by_16(rTmp, rTmp0, *rIn);
    *rIn = rTmp << 2;
}


void ps_all_pass_fract_delay_filter_type_II(UInt32 *delayBufIndex,
        Int32 sb_delay,
        const Int32 *ppFractDelayPhaseFactorSer,
        Int32 ***pppRealDelayRBufferSer,
        Int32 ***pppImagDelayRBufferSer,
        Int32 *rIn,
        Int32 *iIn,
        Int32 decayScaleFactor)
{

    Int32 cmplx;
    Int16 rTmp0;
    Int32 rTmp;
    Int32 iTmp;
    Int32 *pt_rTmp;
    Int32 *pt_iTmp;
    const Int16 *pt_delay;



    /*
     *  All pass filters
     *                         2
     *                        ___  Q_fract(k,m)*z^(-d(m))  -  a(m)*g_decay_slope(k)
     *   z^(-2)*phi_fract(k)* | |  ------------------------------------------------
     *                        m=0  1  - a(m)*g_decay_slope(k)*Q_fract(k,m)*z^(-d(m))
     *
     *
     *    Fractional delay matrix:
     *
     *    Q_fract(k,m) = exp(-j*pi*q(m)*f_center(k))       0<= k <= SUBQMF_GROUPS
     *
     *    Vectors: a(m), q(m), d(m) are constants
     *
     *    m                              m     0       1       2
     *                                 -------------------------------
     *    delay length                 d(m) == 3       4       5      (Fs > 32 KHz)
     *    fractional delay length      q(m) == 0.43    0.75    0.347
     *    filter coefficient           a(m) == 0.65144 0.56472 0.48954
     *
     *             g_decay_slope(k) is given
     */


    Int32 tmp_r;
    Int32 tmp_i;

    pt_rTmp = &pppRealDelayRBufferSer[0][*(delayBufIndex)][sb_delay];
    pt_iTmp = &pppImagDelayRBufferSer[0][*(delayBufIndex++)][sb_delay];

    cmplx  = *(ppFractDelayPhaseFactorSer++);        /* Q_fract(k,m)  */
    pt_delay = aRevLinkDecaySerCoeff[decayScaleFactor];
    tmp_r = *pt_rTmp << 1;
    tmp_i = *pt_iTmp << 1;

    rTmp = cmplx_mul32_by_16(tmp_r, -tmp_i,  cmplx);
    rTmp0  = *(pt_delay++);
    iTmp = cmplx_mul32_by_16(tmp_i,  tmp_r,  cmplx);


    iTmp     =  fxp_mac32_by_16(-*iIn << 1, rTmp0, iTmp);  /* Q_fract(k,m)*y(n-1) - a(m)*g_decay_slope(k)*x(n) */
    *pt_iTmp =  fxp_mac32_by_16(iTmp << 1, rTmp0, *iIn);   /* y(n) = x(n) + a(m)*g_decay_slope(k)*( Q_fract(k,m)*y(n-1) - a(m)*g_decay_slope(k)*x(n)) */
    *iIn = iTmp;

    rTmp     =  fxp_mac32_by_16(-*rIn << 1, rTmp0, rTmp);
    *pt_rTmp =  fxp_mac32_by_16(rTmp << 1, rTmp0, *rIn);
    *rIn = rTmp;

    pt_rTmp = &pppRealDelayRBufferSer[1][*(delayBufIndex)][sb_delay];
    pt_iTmp = &pppImagDelayRBufferSer[1][*(delayBufIndex++)][sb_delay];


    cmplx  = *(ppFractDelayPhaseFactorSer++);        /* Q_fract(k,m)  */
    tmp_r = *pt_rTmp << 1;
    tmp_i = *pt_iTmp << 1;

    rTmp = cmplx_mul32_by_16(tmp_r, -tmp_i,  cmplx);
    rTmp0  = *(pt_delay++);
    iTmp = cmplx_mul32_by_16(tmp_i,  tmp_r,  cmplx);
    iTmp     =  fxp_mac32_by_16(-*iIn << 1, rTmp0, iTmp);  /* Q_fract(k,m)*y(n-1) - a(m)*g_decay_slope(k)*x(n) */
    *pt_iTmp =  fxp_mac32_by_16(iTmp << 1, rTmp0, *iIn);   /* y(n) = x(n) + a(m)*g_decay_slope(k)*( Q_fract(k,m)*y(n-1) - a(m)*g_decay_slope(k)*x(n)) */
    *iIn = iTmp;

    rTmp     =  fxp_mac32_by_16(-*rIn << 1, rTmp0, rTmp);
    *pt_rTmp =  fxp_mac32_by_16(rTmp << 1, rTmp0, *rIn);
    *rIn = rTmp;

    pt_rTmp = &pppRealDelayRBufferSer[2][*(delayBufIndex)][sb_delay];
    pt_iTmp = &pppImagDelayRBufferSer[2][*(delayBufIndex)][sb_delay];


    cmplx  = *(ppFractDelayPhaseFactorSer);        /* Q_fract(k,m)  */
    tmp_r = *pt_rTmp << 1;
    tmp_i = *pt_iTmp << 1;

    rTmp = cmplx_mul32_by_16(tmp_r, -tmp_i,  cmplx);
    rTmp0  = *(pt_delay);
    iTmp = cmplx_mul32_by_16(tmp_i,  tmp_r,  cmplx);


    iTmp     =  fxp_mac32_by_16(-*iIn, rTmp0, iTmp);    /* Q_fract(k,m)*y(n-1) - a(m)*g_decay_slope(k)*x(n) */
    *pt_iTmp =  fxp_mac32_by_16(iTmp, rTmp0, *iIn);     /* y(n) = x(n) + a(m)*g_decay_slope(k)*( Q_fract(k,m)*y(n-1) - a(m)*g_decay_slope(k)*x(n)) */
    *iIn = iTmp << 2;

    rTmp     =  fxp_mac32_by_16(-*rIn, rTmp0, rTmp);
    *pt_rTmp =  fxp_mac32_by_16(rTmp, rTmp0, *rIn);
    *rIn = rTmp << 2;

}



#endif


#endif

