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

 Filename: ps_init_stereo_mixing.c

------------------------------------------------------------------------------
 REVISION HISTORY


 Who:                                   Date: MM/DD/YYYY
 Description:

------------------------------------------------------------------------------
 INPUT AND OUTPUT DEFINITIONS



------------------------------------------------------------------------------
 FUNCTION DESCRIPTION

      initialize mixing procedure  type Ra, type Rb is not supported

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
#include    "fxp_mul32.h"

#include    "aac_mem_funcs.h"
#include    "pv_sine.h"
#include    "s_ps_dec.h"
#include    "ps_all_pass_filter_coeff.h"
#include    "ps_init_stereo_mixing.h"

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

/*
;
;  c(b) = 10^(iid(b)/20)
;
;  Intensity differences
;
;                  sqrt(2)
;   c_1(b) = ----------------
;            sqrt( 1 + c^2(b))
;
;               sqrt(2)*c(b)
;   c_2(b) = ----------------
;            sqrt( 1 + c^2(b))
;
*/



#define R_SHIFT     30
#define Q30_fmt(x)   (Int32)(x*((Int32)1<<R_SHIFT) + (x>=0?0.5F:-0.5F))

const Int32 scaleFactors[NO_IID_LEVELS] =
{
    Q30_fmt(1.411983f),  Q30_fmt(1.403138f),  Q30_fmt(1.386877f),
    Q30_fmt(1.348400f),  Q30_fmt(1.291249f),  Q30_fmt(1.196037f),
    Q30_fmt(1.107372f),  Q30_fmt(1.000000f),  Q30_fmt(0.879617f),
    Q30_fmt(0.754649f),  Q30_fmt(0.576780f),  Q30_fmt(0.426401f),
    Q30_fmt(0.276718f),  Q30_fmt(0.176645f),  Q30_fmt(0.079402f)
};

const Int32 scaleFactorsFine[NO_IID_LEVELS_FINE] =
{
    Q30_fmt(1.414207f),  Q30_fmt(1.414191f),  Q30_fmt(1.414143f),
    Q30_fmt(1.413990f),  Q30_fmt(1.413507f),  Q30_fmt(1.411983f),
    Q30_fmt(1.409773f),  Q30_fmt(1.405395f),  Q30_fmt(1.396780f),
    Q30_fmt(1.380053f),  Q30_fmt(1.348400f),  Q30_fmt(1.313920f),
    Q30_fmt(1.264310f),  Q30_fmt(1.196037f),  Q30_fmt(1.107372f),
    Q30_fmt(1.000000f),  Q30_fmt(0.879617f),  Q30_fmt(0.754649f),
    Q30_fmt(0.633656f),  Q30_fmt(0.523081f),  Q30_fmt(0.426401f),
    Q30_fmt(0.308955f),  Q30_fmt(0.221375f),  Q30_fmt(0.157688f),
    Q30_fmt(0.111982f),  Q30_fmt(0.079402f),  Q30_fmt(0.044699f),
    Q30_fmt(0.025145f),  Q30_fmt(0.014141f),  Q30_fmt(0.007953f),
    Q30_fmt(0.004472f)
};


/*
 *  alphas ranged between 0 and pi/2
 *  alpha(b) = (1/2)*arccos( gamma(b))
 *
 *    b   0    1      2        3        4      5        6     7
 *  gamma 1 0.937  0.84118  0.60092  0.36764   0    -0.589   -1
 *
 */



const Int32 scaled_alphas[NO_ICC_LEVELS] =
{
    Q30_fmt(0.00000000000000f),  Q30_fmt(0.12616764875355f),
    Q30_fmt(0.20199707286122f),  Q30_fmt(0.32744135137762f),
    Q30_fmt(0.42225800677370f),  Q30_fmt(0.55536025173035f),
    Q30_fmt(0.77803595530059f),  Q30_fmt(1.11072050346071f)
};

const Int32 cos_alphas[NO_ICC_LEVELS] =
{
    Q30_fmt(1.00000000000000f),  Q30_fmt(0.98412391153249f),
    Q30_fmt(0.95947390717984f),  Q30_fmt(0.89468446298319f),
    Q30_fmt(0.82693418207478f),  Q30_fmt(0.70710689672598f),
    Q30_fmt(0.45332071670080f),  Q30_fmt(0.00000032679490f)
};

const Int32 sin_alphas[NO_ICC_LEVELS] =
{
    Q30_fmt(0.00000000000000f),  Q30_fmt(0.17748275057029f),
    Q30_fmt(0.28179748302823f),  Q30_fmt(0.44669868110000f),
    Q30_fmt(0.56229872711603f),  Q30_fmt(0.70710666564709f),
    Q30_fmt(0.89134747871404f),  Q30_fmt(1.00000000000000f)
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

Int32 ps_init_stereo_mixing(STRUCT_PS_DEC *pms,
                            Int32 env,
                            Int32 usb)
{
    Int32   group;
    Int32   bin;
    Int32   noIidSteps;
    Int32   tmp;

    Int32   invEnvLength;
    const Int32  *pScaleFactors;
    Int32   scaleR;
    Int32   scaleL;
    Int32   cos_alpha;
    Int32   sin_alpha;
    Int32   beta;
    Int32   cos_beta;
    Int32   sin_beta;
    Int32   temp1;
    Int32   temp2;
    Int32   *ptr_tmp;
    Int32   h11;
    Int32   h12;
    Int32   h21;
    Int32   h22;

    if (pms->bFineIidQ)
    {
        noIidSteps = NO_IID_STEPS_FINE;     /*  NO_IID_STEPS_FINE == 15  */
        pScaleFactors = scaleFactorsFine;
    }
    else
    {
        noIidSteps = NO_IID_STEPS;          /*  NO_IID_STEPS == 7   */
        pScaleFactors = scaleFactors;
    }

    if (env == 0)
    {
        pms->lastUsb = pms->usb;
        pms->usb = usb;
        if (usb != pms->lastUsb && pms->lastUsb != 0)
        {
            return(-1);

        }
    }

    invEnvLength =  pms->aEnvStartStop[env + 1] - pms->aEnvStartStop[env];

    if (invEnvLength == (Int32) pms->noSubSamples)
    {
        invEnvLength = pms->invNoSubSamples;
    }
    else
    {
        invEnvLength = Q30_fmt(1.0f) / invEnvLength;
    }

    if (invEnvLength == 32)     /*  more likely value  */
    {
        for (group = 0; group < NO_IID_GROUPS; group++)      /* == 22 */
        {
            bin = bins2groupMap[group];

            /*
             *  c(b) = 10^(iid(b)/20)
             */

            tmp = pms->aaIidIndex[env][bin];

            /*
             *  Intensity differences
             *
             *                  sqrt(2)
             *   c_1(b) = ----------------
             *            sqrt( 1 + c^2(b))
             *
             */
            scaleR = pScaleFactors[noIidSteps + tmp];

            /*
             *               sqrt(2)*c(b)
             *   c_2(b) = ----------------
             *            sqrt( 1 + c^2(b))
             *
             */

            scaleL = pScaleFactors[noIidSteps - tmp];


            /*
             *  alpha(b) = (1/2)*arccos( gamma(b))
             */
            tmp = pms->aaIccIndex[env][bin];

            cos_alpha = cos_alphas[ tmp];
            sin_alpha = sin_alphas[ tmp];

            /*
             *   beta(b) = alpha(b)/sqrt(2)*( c_1(b) - c_2(b))
             */

            beta   = fxp_mul32_Q30(scaled_alphas[ tmp], (scaleR - scaleL));

            cos_beta = pv_cosine(beta);
            sin_beta = pv_sine(beta);

            temp1 = fxp_mul32_Q30(cos_beta, cos_alpha);
            temp2 = fxp_mul32_Q30(sin_beta, sin_alpha);


            /*
             *  h11(b) = cos( alpha(b) +  beta(b))* c_2(b)
             *  h12(b) = cos(  beta(b) - alpha(b))* c_1(b)
             */

            h11 = fxp_mul32_Q30(scaleL, (temp1 - temp2));
            h12 = fxp_mul32_Q30(scaleR, (temp1 + temp2));

            temp1 = fxp_mul32_Q30(sin_beta, cos_alpha);
            temp2 = fxp_mul32_Q30(cos_beta, sin_alpha);

            /*
             *  h21(b) = sin( alpha(b) +  beta(b))* c_2(b)
             *  h22(b) = sin(  beta(b) - alpha(b))* c_1(b)
             */

            h21 = fxp_mul32_Q30(scaleL, (temp1 + temp2));
            h22 = fxp_mul32_Q30(scaleR, (temp1 - temp2));


            /*
             *   Linear interpolation
             *
             *                                       Hij(k, n_e+1) - Hij(k, n_e)
             *    Hij(k,n) = Hij(k, n_e) + (n - n_e)*---------------------------
             *                                              n_e+1 - n_e
             */

            ptr_tmp = &pms->h11Prev[group];
            pms->H11[group]       = *ptr_tmp;
            pms->deltaH11[group]  = (h11 - *ptr_tmp) >> 5;
            *ptr_tmp              = h11;

            ptr_tmp = &pms->h12Prev[group];
            pms->H12[group]       = *ptr_tmp;
            pms->deltaH12[group]  = (h12 - *ptr_tmp) >> 5;
            *ptr_tmp              = h12;

            ptr_tmp = &pms->h21Prev[group];
            pms->H21[group]       = *ptr_tmp;
            pms->deltaH21[group]  = (h21 - *ptr_tmp) >> 5;
            *ptr_tmp              = h21;

            ptr_tmp = &pms->h22Prev[group];
            pms->H22[group]       = *ptr_tmp;
            pms->deltaH22[group]  = (h22 - *ptr_tmp) >> 5;
            *ptr_tmp              = h22;


        } /* groups loop */
    }
    else
    {

        for (group = 0; group < NO_IID_GROUPS; group++)      /* == 22 */
        {
            bin = bins2groupMap[group];

            /*
             *  c(b) = 10^(iid(b)/20)
             */

            tmp = pms->aaIidIndex[env][bin];

            /*
             *  Intensity differences
             *
             *                  sqrt(2)
             *   c_1(b) = ----------------
             *            sqrt( 1 + c^2(b))
             *
             */
            scaleR = pScaleFactors[noIidSteps + tmp];

            /*
             *               sqrt(2)*c(b)
             *   c_2(b) = ----------------
             *            sqrt( 1 + c^2(b))
             *
             */

            scaleL = pScaleFactors[noIidSteps - tmp];


            /*
             *  alpha(b) = (1/2)*arccos( gamma(b))
             */
            tmp = pms->aaIccIndex[env][bin];

            cos_alpha = cos_alphas[ tmp];
            sin_alpha = sin_alphas[ tmp];

            /*
             *   beta(b) = alpha(b)/sqrt(2)*( c_1(b) - c_2(b))
             */

            beta   = fxp_mul32_Q30(scaled_alphas[ tmp], (scaleR - scaleL));

            cos_beta = pv_cosine(beta);
            sin_beta = pv_sine(beta);

            temp1 = fxp_mul32_Q30(cos_beta, cos_alpha);
            temp2 = fxp_mul32_Q30(sin_beta, sin_alpha);


            /*
             *  h11(b) = cos( alpha(b) +  beta(b))* c_2(b)
             *  h12(b) = cos(  beta(b) - alpha(b))* c_1(b)
             */

            h11 = fxp_mul32_Q30(scaleL, (temp1 - temp2));
            h12 = fxp_mul32_Q30(scaleR, (temp1 + temp2));

            temp1 = fxp_mul32_Q30(sin_beta, cos_alpha);
            temp2 = fxp_mul32_Q30(cos_beta, sin_alpha);

            /*
             *  h21(b) = sin( alpha(b) +  beta(b))* c_2(b)
             *  h22(b) = sin(  beta(b) - alpha(b))* c_1(b)
             */

            h21 = fxp_mul32_Q30(scaleL, (temp1 + temp2));
            h22 = fxp_mul32_Q30(scaleR, (temp1 - temp2));


            /*
             *   Linear interpolation
             *
             *                                       Hij(k, n_e+1) - Hij(k, n_e)
             *    Hij(k,n) = Hij(k, n_e) + (n - n_e)*---------------------------
             *                                              n_e+1 - n_e
             */

            ptr_tmp = &pms->h11Prev[group];
            pms->deltaH11[group]  = fxp_mul32_Q30((h11 - *ptr_tmp), invEnvLength);
            pms->H11[group]       = *ptr_tmp;
            *ptr_tmp              = h11;

            ptr_tmp = &pms->h12Prev[group];
            pms->deltaH12[group]  = fxp_mul32_Q30((h12 - *ptr_tmp), invEnvLength);
            pms->H12[group]       = *ptr_tmp;
            *ptr_tmp              = h12;

            ptr_tmp = &pms->h21Prev[group];
            pms->deltaH21[group]  = fxp_mul32_Q30((h21 - *ptr_tmp), invEnvLength);
            pms->H21[group]       = *ptr_tmp;
            *ptr_tmp              = h21;

            ptr_tmp = &pms->h22Prev[group];
            pms->deltaH22[group]  = fxp_mul32_Q30((h22 - *ptr_tmp), invEnvLength);
            pms->H22[group]       = *ptr_tmp;
            *ptr_tmp              = h22;


        } /* groups loop */
    }


    return (0);

} /* END ps_init_stereo_mixing */

#endif


#endif

