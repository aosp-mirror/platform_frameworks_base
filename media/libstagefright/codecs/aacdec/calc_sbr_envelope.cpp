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
 Filename: calc_sbr_envelope.c

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


#include    "calc_sbr_envelope.h"
#include    "sbr_envelope_calc_tbl.h"
#include    "sbr_create_limiter_bands.h"
#include    "aac_mem_funcs.h"

#include    "fxp_mul32.h"
#include    "pv_normalize.h"

#include    "sbr_aliasing_reduction.h"

/*----------------------------------------------------------------------------
; MACROS
; Define module specific macros here
----------------------------------------------------------------------------*/


/*----------------------------------------------------------------------------
; DEFINES
; Include all pre-processor statements here. Include conditional
; compile variables also.
----------------------------------------------------------------------------*/
#include    "pv_sqrt.h"

#include    "pv_div.h"
#include    "fxp_mul32.h"
#include    "pv_normalize.h"

#define Q30fmt(x)   (Int32)(x*((Int32)1<<30) + (x>=0?0.5F:-0.5F))
#define Q28fmt(x)   (Int32)(x*((Int32)1<<28) + (x>=0?0.5F:-0.5F))
#define Q15fmt(x)   (Int32)(x*((Int32)1<<15) + (x>=0?0.5F:-0.5F))


/*----------------------------------------------------------------------------
; LOCAL FUNCTION DEFINITIONS
; Function Prototype declaration
----------------------------------------------------------------------------*/
#ifdef __cplusplus
extern "C"
{
#endif

    void envelope_application_LC(Int32  *aBufR,
    Int32  *nrg_gain_man,
    Int32  *nrg_gain_exp,
    Int32  *noise_level_man,
    Int32  *noise_level_exp,
    Int32  *nrg_tone_man,
    Int32  *nrg_tone_exp,
    Int32  band_nrg_tone_detector,
    const Int32 *frame_info,
    Int32  *harm_index,
    Int32  *phase_index,
    Int32  i,
    Int32  lowSubband,
    Int32  noSubbands,
    Int32  noNoiseFlag);


    void energy_estimation_LC(Int32 *aBufR,
                              Int32 *nrg_est_man,
                              Int32 *nrg_est_exp,
                              const Int32 *frame_info,
                              Int32 i,
                              Int32 k,
                              Int32 c,
                              Int32 ui2);

#ifdef HQ_SBR


    void envelope_application(Int32  *aBufR,
                              Int32  *aBufI,
                              Int32  *nrg_gain_man,
                              Int32  *nrg_gain_exp,
                              Int32  *noise_level_man,
                              Int32  *noise_level_exp,
                              Int32  *nrg_tone_man,
                              Int32  *nrg_tone_exp,
                              Int32 *fBuf_man[64],
                              Int32 *fBuf_exp[64],
                              Int32 *fBufN_man[64],
                              Int32 *fBufN_exp[64],
                              const Int32 *frame_info,
                              Int32  *harm_index,
                              Int32  *phase_index,
                              Int32  i,
                              Int32  lowSubband,
                              Int32  noSubbands,
                              Int32  noNoiseFlag,
                              Int32  band_nrg_tone_detector,
                              Int32  maxSmoothLength,
                              Int32  smooth_length);


    void energy_estimation(Int32 *aBufR,
                           Int32 *aBufI,
                           Int32 *nrg_est_man,
                           Int32 *nrg_est_exp,
                           const Int32 *frame_info,
                           Int32 i,
                           Int32 k,
                           Int32 c,
                           Int32 ui2);

#endif

#ifdef __cplusplus
}
#endif

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

void calc_sbr_envelope(SBR_FRAME_DATA *frameData,
                       Int32 *aBufR,
                       Int32 *aBufI,
                       Int freqBandTable1[2][MAX_FREQ_COEFFS + 1],
                       const Int32 *nSfb,
                       Int32 freqBandTable2[MAX_NOISE_COEFFS + 1],
                       Int32 nNBands,
                       Int32 reset,
                       Int32 *degreeAlias,
                       Int32 *harm_index,
                       Int32 *phase_index,
                       Int32 hFp[64],
                       Int32 *sUp,
                       Int32 limSbc[][13],
                       Int32 *gateMode,
#ifdef HQ_SBR
                       Int32 *fBuf_man[64],
                       Int32 *fBuf_exp[64],
                       Int32 *fBufN_man[64],
                       Int32 *fBufN_exp[64],
#endif
                       Int32 scratch_mem[][64],
                       struct PATCH Patch,
                       Int32  sqrt_cache[][4],
                       Int32  LC_flag)
{

    Int32 c;
    Int32 li;
    Int32 ui;
    Int32 i;
    Int32 j;
    Int32 k = 0;
    Int32 l;
    Int m = 0;
    Int kk = 0;
    Int o;
    Int next = -1;
    Int32 ui2;
    Int flag;
    Int noNoiseFlag;
    Int *ptr;


    UInt32 nrg = 0;
    Int32 nrg_exp = 0;
    struct intg_div   quotient;
    struct intg_sqrt  root_sq;

    Int32 aux1;

    Int32 *nL_man       = frameData->sbrNoiseFloorLevel_man;
    Int32 *nL_exp       = frameData->sbrNoiseFloorLevel_exp;

    Int32 *sfb_nrg_man  = frameData->iEnvelope_man;
    Int32 *sfb_nrg_exp  = frameData->iEnvelope_exp;

    Int32 tmp_q1;
    Int32 tmp_q2;

    Int32 g_max_man;
    Int32 g_max_exp;

    Int32 p_ref_man;
    Int32 p_ref_exp;

    Int32 p_est_man;
    Int32 p_est_exp;

    Int32 p_adj_man;
    Int32 p_adj_exp;
    Int32 avg_gain;

    Int32 boost_gain_q;

    Int32 band_nrg_tone_detector;

    Int32 *nrg_est_man     = scratch_mem[0];
    Int32 *nrg_est_exp     = scratch_mem[1];
    Int32 *nrg_ref_man     = scratch_mem[2];
    Int32 *nrg_ref_exp     = scratch_mem[3];
    Int32 *nrg_gain_man    = scratch_mem[4];
    Int32 *nrg_gain_exp    = scratch_mem[5];
    Int32 *noise_level_man = scratch_mem[6];
    Int32 *noise_level_exp = scratch_mem[7];
    Int32 *nrg_tone_man    = scratch_mem[8];
    Int32 *nrg_tone_exp    = scratch_mem[9];
    Int32 *hF              = scratch_mem[10];



    const Int32 *frame_info = frameData->frameInfo;
    Int32 int_mode          = frameData->sbr_header.interpolFreq;





    Int32 dontUseTheseGainValues[64];

#ifdef HQ_SBR

    Int32 n;
    Int32 smooth_length;
    Int32 smoothingLength   = frameData->sbr_header.smoothingLength;
    Int32 maxSmoothLength   = smoothLengths[0];

#endif

    Int32 limiterBand       = frameData->sbr_header.limiterBands;
    Int32 limiterGains      = frameData->sbr_header.limiterGains;
    Int32 *addHarmonics     = frameData->addHarmonics;

    Int32 lowSubband        = freqBandTable1[LOW_RES][0];
    Int32 noSubbands        = freqBandTable1[LOW_RES][nSfb[LOW_RES]] - lowSubband;
    Int32 nEnv              = frame_info[0];
    Int32 sEnv              = frame_info[(nEnv + 1)<<1];

    /* ensure that noSubbands in the range [0,64] */
    noSubbands = (noSubbands >> 31) ^ noSubbands;
    if (noSubbands > 64)
    {
        noSubbands = 64;
    }

    if (reset)
    {
        *sUp = 1;
        *phase_index = 0;
        sbr_create_limiter_bands(limSbc,
                                 gateMode,
                                 freqBandTable1[LOW_RES],
                                 Patch,
                                 nSfb[LOW_RES]);
    }

    /* Mapping. */
    pv_memset((void*)hF, 0, (sizeof(*hF) << 6));

    ptr  = freqBandTable1[HI];
    l = *(ptr++);

    for (i = nSfb[HI]; i != 0; i--)
    {
        k     = *(ptr++);
        j     = ((k + l) >> 1) - lowSubband;
        l   = k;
        hF[j] = *(addHarmonics++);
    }


    /* Envelope adjustment. */

    for (i = 0; i < nEnv; i++)
    {

        if (frame_info[1+i] == frame_info[(nEnv<<1)+4+kk])
        {
            kk++, next++;
        }

        noNoiseFlag = (i == sEnv || i == frameData->prevEnvIsShort) ? 1 : 0;

#ifdef HQ_SBR
        smooth_length = (noNoiseFlag ? 0 : smoothLengths[smoothingLength]);
#endif


        /* Estimate levels. */
        c = 0;
        o = 0;

        band_nrg_tone_detector = 0;

        Int kkkk = freqBandTable1[ frame_info[nEnv+2+i] ][0];

        for (j = 0; j <  nSfb[frame_info[nEnv+2+i]]; j++)
        {
            li = freqBandTable1[ frame_info[nEnv+2+i] ][j    ];
            ui = freqBandTable1[ frame_info[nEnv+2+i] ][j + 1];
            flag = 0;

            for (k = li; k < ui; k++)
            {                               /* Calculate the average energy over the current envelope, */
                ui2   = (frame_info[1+i] << 1);

                if (LC_flag == ON)
                {
                    energy_estimation_LC((Int32 *)aBufR,
                                         nrg_est_man,
                                         nrg_est_exp,
                                         frame_info,
                                         i,
                                         k - kkkk,
                                         c,
                                         ui2);
                }
#ifdef HQ_SBR
                else
                {

                    energy_estimation((Int32 *)aBufR,
                                      (Int32 *)aBufI,
                                      nrg_est_man,
                                      nrg_est_exp,
                                      frame_info,
                                      i,
                                      k - kkkk,
                                      c,
                                      ui2);
                }
#endif

                flag = (hF[c] && (i >= sEnv || hFp[c+lowSubband])) ? 1 : flag;
                c++;
            }


            ui2 = freqBandTable2[o+1];

            if (!int_mode)
            {                                /* If no interpolation is used,   */

                tmp_q1 = -100;

                for (k = c - (ui - li); k < c; k++)
                {
                    if (tmp_q1 < nrg_est_exp[k])
                    {
                        tmp_q1 = nrg_est_exp[k];
                    }
                }

                nrg = 0;
                for (k = c - (ui - li); k < c; k++)
                {    /* average the energy in all the QMF bands, */
                    nrg += nrg_est_man[k] >> (tmp_q1 - nrg_est_exp[k]); /* for the whole scalefactor band.  */
                }
                nrg /= (ui - li);
                nrg_exp = tmp_q1;

            }

            c -= (ui - li);

            for (k = 0; k < ui - li; k++)
            {
                o = (k + li >= ui2) ? o + 1 : o;
                ui2 = freqBandTable2[o+1];
                /*
                 *  If no interpolation is used, use the averaged energy from above,
                 *  otherwise do nothing.
                 */


                if (!int_mode)
                {
                    nrg_est_man[c] = nrg;
                    nrg_est_exp[c] = nrg_exp;
                }

                if (LC_flag == ON)
                {
                    nrg_est_exp[c] += 1;

                    if (flag)
                    {
                        dontUseTheseGainValues[k + li - lowSubband] = 1;
                    }
                    else
                    {
                        dontUseTheseGainValues[k + li - lowSubband] = 0;
                    }
                }

                nrg_ref_man[c] = sfb_nrg_man[m];
                nrg_ref_exp[c] = sfb_nrg_exp[m];

                /*
                 *  compute nL/(1 + nL);   where nL = nL_man*2^nL_exp
                 */
                aux1 = next * nNBands + o;

                tmp_q1 = nL_exp[aux1];

                if (tmp_q1 >= 0)
                {
                    pv_div(nL_man[aux1], nL_man[aux1] + (0x3FFFFFFF >> tmp_q1), &quotient);
                }
                else
                {
                    tmp_q1 = nL_man[aux1] >> (-tmp_q1);
                    pv_div(tmp_q1, tmp_q1 + 0x3FFFFFFF, &quotient);
                }

                /*
                 *  tmp_q1 = nL/(1 + nL)*nrg_ref[c];
                 */

                tmp_q1 = fxp_mul32_Q30(quotient.quotient >> quotient.shift_factor,  nrg_ref_man[c]);

                if (flag)
                {
                    /*
                     *  Calculate levels and gain, dependent on whether a synthetic, a sine is present or not.
                     *
                     *  nrg_gain[c]=(float)pv_sqrt( tmp/(nrg_est[c] + 1), sqrt_cache[1] );
                     */


                    pv_div(tmp_q1, nrg_est_man[c] + 1, &quotient);
                    /*
                     *  nrg_est_man[c] is an integer number, while tmp_q1 and quotient.quotient
                     *  are fractions in Q30
                     */

                    tmp_q2 = nrg_ref_exp[c] - nrg_est_exp[c] - quotient.shift_factor - 30;

                    pv_sqrt(quotient.quotient, tmp_q2, &root_sq, sqrt_cache[1]);
                    nrg_gain_man[c] = root_sq.root;     /*  in Q28 format */
                    nrg_gain_exp[c] = root_sq.shift_factor;


                    /*
                     *  nrg_tone[c]=(float)( (hF[c] && (i >= sEnv || hFp[c+lowSubband])) ?
                     *                          pv_sqrt(nrg_ref[c]/(1+tmp_nL), sqrt_cache[2]) : 0);
                     */
                    if (hF[c] && (i >= sEnv || hFp[c+lowSubband]))
                    {
                        /*
                         *  nrg_ref[c] and  nL, as well as quotient.quotient
                         *  are fractions in Q30
                         */

                        /*  aux1 == next*nNBands + o */

                        tmp_q2 = nL_exp[aux1];
                        /*
                         *  nrg_ref[c]/(1+tmp_nL)
                         */

                        if (tmp_q2 >= 0)
                        {
                            pv_div(nrg_ref_man[c], nL_man[aux1] + (0x3FFFFFFF >> tmp_q2), &quotient);
                        }
                        else
                        {
                            tmp_q2 = nL_man[aux1] >> (-tmp_q2);
                            pv_div(nrg_ref_man[c], tmp_q2 + 0x3FFFFFFF, &quotient);
                            tmp_q2 = 0;     /* exponent has been applied to the sum ((man>>exp) + 1)  */
                        }

                        tmp_q2 = nrg_ref_exp[c] - tmp_q2 - quotient.shift_factor;

                        pv_sqrt(quotient.quotient, tmp_q2, &root_sq, sqrt_cache[2]);
                        nrg_tone_man[c]    = root_sq.root;
                        nrg_tone_exp[c]    = root_sq.shift_factor;

                    }
                    else
                    {
                        nrg_tone_man[c]    = 0;
                        nrg_tone_exp[c]    = 0;
                    }

                }
                else
                {
                    if (noNoiseFlag)
                    {
                        /*
                         * nrg_gain[c] = (float) pv_sqrt(nrg_ref[c] /(nrg_est[c] + 1), sqrt_cache[3]);
                         */

                        pv_div(nrg_ref_man[c], nrg_est_man[c] + 1, &quotient);

                        /*
                         *  nrg_est_man[c] is an integer number, while nrg_ref_man[c] and
                         *  quotient.quotient are fractions in Q30
                         */

                        tmp_q2 = nrg_ref_exp[c] - nrg_est_exp[c] - quotient.shift_factor - 30;

                        pv_sqrt(quotient.quotient, tmp_q2, &root_sq, sqrt_cache[3]);
                        nrg_gain_man[c] = root_sq.root;
                        nrg_gain_exp[c] = root_sq.shift_factor;

                    }
                    else
                    {
                        /*
                         *  nrg_gain[c] = (float) pv_sqrt(nrg_ref[c]/((nrg_est[c] + 1)*(1+tmp_nL)), sqrt_cache[4]);
                         */
                        /*  aux1 == next*nNBands + o */

                        tmp_q2 = nL_exp[aux1];
                        /*
                         *  nrg_ref[c]/((nrg_est[c] + 1)*(1+tmp_nL))
                         */

                        if (nrg_est_man[c] == 0)
                        {
                            tmp_q2 = 0;     /*  avoid division by 0 in next if-else, this could be due to
                                                rounding noise */
                        }


                        if (tmp_q2 >= 0)
                        {

                            tmp_q2 = fxp_mul32_Q30(nrg_est_man[c] + 1, nL_man[aux1] + (0x3FFFFFFF >> tmp_q2));
                            pv_div(nrg_ref_man[c], tmp_q2, &quotient);
                            /*
                             *  nrg_est_man[c] is an integer number, while nrg_ref_man[c] and
                             *  quotient.quotient are fractions in Q30
                             */
                            tmp_q2 = nrg_ref_exp[c] - quotient.shift_factor - 30 - nL_exp[aux1];
                            if (nrg_est_man[c])
                            {
                                tmp_q2 -=  nrg_est_exp[c];
                            }

                            tmp_q2 = nrg_ref_exp[c] - nrg_est_exp[c] - quotient.shift_factor - 30 - nL_exp[aux1];
                        }
                        else
                        {
                            if (tmp_q2 > - 10)
                            {
                                tmp_q2 = nL_man[aux1] >> (-tmp_q2);

                                tmp_q2 = fxp_mul32_Q30(nrg_est_man[c] + 1, tmp_q2 + 0x3FFFFFFF);
                            }
                            else
                            {
                                tmp_q2 = nrg_est_man[c] + 1;
                            }


                            pv_div(nrg_ref_man[c], tmp_q2, &quotient);
                            /*
                             *  nrg_est_man[c] is an integer number, while nrg_ref_man[c] and
                             *  quotient.quotient are fractions in Q30
                             */

                            tmp_q2 = nrg_ref_exp[c] - quotient.shift_factor - 30;
                            if (nrg_est_man[c])
                            {
                                tmp_q2 -=  nrg_est_exp[c];
                            }

                        }

                        pv_sqrt(quotient.quotient, tmp_q2, &root_sq, sqrt_cache[4]);
                        nrg_gain_man[c] = root_sq.root;
                        nrg_gain_exp[c] = root_sq.shift_factor;

                    }

                    nrg_tone_man[c]    = 0;
                    nrg_tone_exp[c]    = -100;

                }

                band_nrg_tone_detector |= nrg_tone_man[c];   /*  detect any tone activity  */

                pv_sqrt(tmp_q1, nrg_ref_exp[c], &root_sq, sqrt_cache[5]);
                noise_level_man[c] = root_sq.root;
                noise_level_exp[c] = root_sq.shift_factor;

                c++;

            }   /* ---- end-for-loop (k) ------ */
            m++;

        }   /* -------- Estimate levels end-for-loop (j) ----- */



        /*
         *      Limiter
         */


        for (c = 0; c < gateMode[limiterBand]; c++)
        {

            p_ref_man = 0;
            p_est_man = 0;

            /*
             *  get max exponent for the reference and estimated energy
             */
            p_ref_exp = -100;
            p_est_exp = -100;

            for (k = limSbc[limiterBand][c]; k < limSbc[limiterBand][c + 1]; k++)
            {
                if (p_ref_exp < nrg_ref_exp[k])
                {
                    p_ref_exp = nrg_ref_exp[k];    /* max */
                }
                if (p_est_exp < nrg_est_exp[k])
                {
                    p_est_exp = nrg_est_exp[k];    /* max */
                }
            }

            k -= limSbc[limiterBand][c];     /*  number of element used in the addition */

            while (k != 0)      /*  bit guard protection depends on log2(k)  */
            {
                k >>= 1;
                p_ref_exp++;       /*  add extra bit-overflow-guard, nrg_ref_exp is in Q30 format */
            }


            for (k = limSbc[limiterBand][c]; k < limSbc[limiterBand][c + 1]; k++)
            {   /*Calculate the average gain for the current limiter band.*/
                p_ref_man += (nrg_ref_man[k] >> (p_ref_exp - nrg_ref_exp[k]));
                p_est_man += (nrg_est_man[k] >> (p_est_exp - nrg_est_exp[k]));

            }

            if (p_est_man)
            {
                /*
                 *  "average gain" (not equal to average of nrg_gain)
                 */
                pv_div(p_ref_man, p_est_man, &quotient);

                tmp_q2 = p_ref_exp - 30 - p_est_exp - quotient.shift_factor;

                /*
                 *  avg_gain = sqrt(p_ref/p_est)
                 */
                pv_sqrt(quotient.quotient, tmp_q2, &root_sq, sqrt_cache[6]);
                avg_gain  = root_sq.root;
                g_max_exp = root_sq.shift_factor;

                /*
                 *  maximum gain allowed is calculated from table.
                 */

                /*
                 *  g_max = avg_gain * limGains[limiterGains];
                 */

                g_max_man = fxp_mul32_Q30(avg_gain, limGains[limiterGains]);   /*  table is in Q30 */

                if (limiterGains == 3)
                {
                    g_max_exp = limGains[4];
                }

                tmp_q1 = g_max_exp >= 16 ? g_max_exp : 16;

                tmp_q2 = g_max_man >> (tmp_q1 - g_max_exp);
                tmp_q1 = Q28fmt(1.52587890625F) >> (tmp_q1 - 16);

                if (tmp_q2 > tmp_q1)
                {
                    /* upper limit, +100 dB */
                    g_max_man = Q28fmt(1.52587890625F);
                    g_max_exp = 16;
                }
            }
            else
            {
                /*  Qfmt(1.52587890625F)    exp = 16 */
                g_max_man = Q28fmt(1.52587890625F);
                g_max_exp = 16;
            }

            /*
             *  Compute Adjusted power p_adj
             */
            for (k = limSbc[limiterBand][c]; k < limSbc[limiterBand][c + 1]; k++)
            {

                tmp_q1 = g_max_exp >= nrg_gain_exp[k] ? g_max_exp : nrg_gain_exp[k];

                tmp_q2 = g_max_man >> (tmp_q1 - g_max_exp);
                tmp_q1 = nrg_gain_man[k] >> (tmp_q1 - nrg_gain_exp[k]);
                /*
                 *  if(g_max <= nrg_gain[k])
                 */
                if (tmp_q2 <= tmp_q1)
                {
                    tmp_q1 = fxp_mul32_Q28(noise_level_man[k], g_max_man);
                    pv_div(tmp_q1, nrg_gain_man[k], &quotient);
                    noise_level_man[k] = quotient.quotient >> 2;   /* in Q28 */
                    noise_level_exp[k] = noise_level_exp[k] + g_max_exp - quotient.shift_factor - nrg_gain_exp[k];

                    nrg_gain_man[k] =  g_max_man;       /* gains with noise supression */
                    nrg_gain_exp[k] =  g_max_exp;
                }
            }

            p_adj_exp = -100;

            for (k = limSbc[limiterBand][c]; k < limSbc[limiterBand][c + 1]; k++)
            {
                tmp_q1 = nrg_est_exp[k] + (nrg_gain_exp[k] << 1) + 28;  /* 28 to match shift down by mult32_Q28  */

                if (p_adj_exp < tmp_q1)
                {
                    p_adj_exp = tmp_q1;
                }
                if (nrg_tone_man[k])
                {
                    tmp_q1 = (nrg_tone_exp[k] << 1);
                    if (p_adj_exp < tmp_q1)
                    {
                        p_adj_exp = tmp_q1;
                    }
                }
                else if (!noNoiseFlag)
                {
                    tmp_q1 = (noise_level_exp[k] << 1);

                    if (p_adj_exp < tmp_q1)
                    {
                        p_adj_exp = tmp_q1;
                    }
                }
            }

            p_adj_exp += 1; /* overflow bit-guard*/

            p_adj_man = 0;

            for (k = limSbc[limiterBand][c]; k < limSbc[limiterBand][c + 1]; k++)
            {
                /*
                 *  p_adj += nrg_gain[k]*nrg_gain[k]*nrg_est[k];
                 */

                if (p_adj_exp - (nrg_est_exp[k] + (nrg_gain_exp[k] << 1)) < 59)
                {
                    tmp_q1 = fxp_mul32_Q28(nrg_gain_man[k], nrg_gain_man[k]);
                    tmp_q1 = fxp_mul32_Q28(tmp_q1, nrg_est_man[k]);
                    p_adj_man += (tmp_q1 >> (p_adj_exp - (nrg_est_exp[k] + (nrg_gain_exp[k] << 1) + 28)));
                }

                if (nrg_tone_man[k])
                {
                    /*
                     *  p_adj += nrg_tone[k]*nrg_tone[k];
                     */
                    if (p_adj_exp - (nrg_tone_exp[k] << 1) < 31)
                    {
                        tmp_q1 = fxp_mul32_Q28(nrg_tone_man[k], nrg_tone_man[k]);
                        p_adj_man += (tmp_q1 >> (p_adj_exp - (nrg_tone_exp[k] << 1)));
                    }
                }
                else if (!noNoiseFlag)
                {
                    /*
                     *  p_adj += noise_level[k]*noise_level[k];
                     */

                    if (p_adj_exp - (noise_level_exp[k] << 1) < 31)
                    {
                        tmp_q1 = fxp_mul32_Q28(noise_level_man[k], noise_level_man[k]);
                        p_adj_man += (tmp_q1 >> (p_adj_exp - (noise_level_exp[k] << 1)));
                    }

                }
            }


            if (p_adj_man)
            {
                pv_div(p_ref_man, p_adj_man, &quotient);
                tmp_q2 = p_ref_exp - p_adj_exp - 58 - quotient.shift_factor;   /*  58 <> Q30 + Q28 */

                pv_sqrt(quotient.quotient, tmp_q2, &root_sq, sqrt_cache[7]);

                if (root_sq.shift_factor > -28)
                {
                    boost_gain_q = root_sq.root << (root_sq.shift_factor + 28);
                }
                else
                {
                    boost_gain_q = root_sq.root >> (-28 - root_sq.shift_factor);
                }

                tmp_q1 = root_sq.shift_factor >= -28 ? root_sq.shift_factor : -28;

                tmp_q2 = root_sq.root >> (tmp_q1 - root_sq.shift_factor);
                tmp_q1 = Q28fmt(1.584893192f) >> (tmp_q1 + 28);


                if (tmp_q2 > tmp_q1)
                {
                    boost_gain_q = Q28fmt(1.584893192f);
                }
            }
            else
            {
                boost_gain_q = Q28fmt(1.584893192f);
            }

            if (band_nrg_tone_detector)
            {
                for (k = limSbc[limiterBand][c]; k < limSbc[limiterBand][c + 1]; k++)
                {
                    nrg_gain_man[k]    = fxp_mul32_Q28(nrg_gain_man[k], boost_gain_q);
                    noise_level_man[k] = fxp_mul32_Q28(noise_level_man[k], boost_gain_q);
                    nrg_tone_man[k]    = fxp_mul32_Q28(nrg_tone_man[k], boost_gain_q);
                }
            }
            else
            {

                for (k = limSbc[limiterBand][c]; k < limSbc[limiterBand][c + 1]; k++)
                {
                    nrg_gain_man[k]    = fxp_mul32_Q28(nrg_gain_man[k], boost_gain_q);
                    noise_level_man[k] = fxp_mul32_Q28(noise_level_man[k], boost_gain_q);
                }


            }

        }   /* Limiter  End for loop (c) */


        if (LC_flag == ON)
        {

            /*
             *          Aliasing correction
             */

            sbr_aliasing_reduction(degreeAlias,
                                   nrg_gain_man,
                                   nrg_gain_exp,
                                   nrg_est_man,
                                   nrg_est_exp,
                                   dontUseTheseGainValues,
                                   noSubbands,
                                   lowSubband,
                                   sqrt_cache,
                                   scratch_mem[3]);

            if (*sUp)     /* Init only done once upon reset */
            {
                *sUp = 0;
            }

            envelope_application_LC((Int32 *)aBufR,
                                    nrg_gain_man,
                                    nrg_gain_exp,
                                    noise_level_man,
                                    noise_level_exp,
                                    nrg_tone_man,
                                    nrg_tone_exp,
                                    band_nrg_tone_detector,
                                    frame_info,
                                    harm_index,
                                    phase_index,
                                    i,
                                    lowSubband,
                                    noSubbands,
                                    noNoiseFlag);
        }
#ifdef HQ_SBR
        else
        {

            if (*sUp)     /* Init only done once upon reset */
            {
                for (n = 0; n < maxSmoothLength; n++)
                {
                    pv_memcpy(fBuf_man[n],     nrg_gain_man, noSubbands*sizeof(*fBuf_man[n]));
                    pv_memcpy(fBufN_man[n], noise_level_man, noSubbands*sizeof(*fBufN_man[n]));
                    pv_memcpy(fBuf_exp[n],     nrg_gain_exp, noSubbands*sizeof(*fBuf_exp[n]));
                    pv_memcpy(fBufN_exp[n], noise_level_exp, noSubbands*sizeof(*fBufN_exp[n]));
                }
                *sUp = 0;
            }


            envelope_application((Int32 *)aBufR,
                                 (Int32 *)aBufI,
                                 nrg_gain_man,
                                 nrg_gain_exp,
                                 noise_level_man,
                                 noise_level_exp,
                                 nrg_tone_man,
                                 nrg_tone_exp,
                                 fBuf_man,
                                 fBuf_exp,
                                 fBufN_man,
                                 fBufN_exp,
                                 frame_info,
                                 harm_index,
                                 phase_index,
                                 i,
                                 lowSubband,
                                 noSubbands,
                                 noNoiseFlag,
                                 band_nrg_tone_detector,
                                 maxSmoothLength,
                                 smooth_length);

        }
#endif

    }   /* -----  Envelope adjustment end for-loop (i) ---- */


    pv_memcpy(&hFp[0] + lowSubband,
              hF,
              (64 - lowSubband)*sizeof(*hF));

    if (sEnv == nEnv)
    {
        frameData->prevEnvIsShort = 0;
    }
    else
    {
        frameData->prevEnvIsShort = -1;
    }


}



/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

void envelope_application_LC(Int32  *aBufR,
                             Int32  *nrg_gain_man,
                             Int32  *nrg_gain_exp,
                             Int32  *noise_level_man,
                             Int32  *noise_level_exp,
                             Int32  *nrg_tone_man,
                             Int32  *nrg_tone_exp,
                             Int32  band_nrg_tone_detector,
                             const Int32 *frame_info,
                             Int32  *harm_index,
                             Int32  *phase_index,
                             Int32  i,
                             Int32  lowSubband,
                             Int32  noSubbands,
                             Int32  noNoiseFlag)
{

    Int32 *ptrReal;
    Int32 sb_gain_man;
    Int32 sb_noise_man;
    Int32 sb_noise_exp;
    Int32 l;
    Int32 k;
    Int32 tmp_q1;
    Int32 tmp_q2;
    Int32 tone_count;
    Int16 tmp_16;
    Int32 indexMinus1;
    Int32 indexPlus1;


    /*
     *          Application
     */

    if (band_nrg_tone_detector)     /* Add tone energy only if energy is detected  */
    {

        /*
         *  pre-calculate tone application
         */
        for (k = 0; k < noSubbands; k++)
        {
            tmp_q2 = (-nrg_tone_exp[k]);
            tmp_q1 = nrg_tone_man[k];
            tmp_q2 = tmp_q1 >> tmp_q2;
            tmp_q1 = fxp_mul32_by_16(tmp_q2, Q15fmt(0.0163f));
            nrg_tone_man[k] = tmp_q2;
            nrg_tone_exp[k] = tmp_q1;
            noise_level_exp[k] += 1;
            nrg_gain_exp[k] += 28;
        }

        for (l = (frame_info[1+i] << 1); l < (frame_info[2+i] << 1); l++)
        {
            ptrReal = (aBufR + l * SBR_NUM_BANDS);

            tone_count = 0;

            indexPlus1  = (*harm_index + 1) & 3;

            if (indexPlus1 & 1)    /*  if indexPlus1 is odd */
            {
                for (k = 0; k < noSubbands; k++)
                {

                    sb_gain_man = nrg_gain_man[k];
                    tmp_q1 = *ptrReal;
                    tmp_q2 = nrg_gain_exp[k];
                    tmp_q1 = fxp_mul32_Q28(tmp_q1, sb_gain_man);

                    if (tmp_q2 < 0)
                    {
                        if (tmp_q2 > -32)
                        {
                            *ptrReal = tmp_q1 >> (-tmp_q2);
                        }
                    }
                    else
                    {
                        *ptrReal = tmp_q1 << tmp_q2;
                    }

                    *phase_index = (*phase_index + 1) & 511;

                    if (!nrg_tone_man[k] && !noNoiseFlag)

                    {
                        tmp_16 = rP_LCx[*phase_index];
                        sb_noise_man = noise_level_man[k];
                        sb_noise_exp = noise_level_exp[k];

                        tmp_q1 = fxp_mul32_by_16(sb_noise_man, tmp_16);

                        if (sb_noise_exp < 0)
                        {
                            if (sb_noise_exp > -32)
                            {
                                *ptrReal += tmp_q1 >> (-sb_noise_exp);
                            }
                        }
                        else
                        {
                            *ptrReal += tmp_q1 << sb_noise_exp;
                        }
                    }

                    tmp_q1 = nrg_tone_man[k];

                    if (*harm_index)
                    {
                        *ptrReal -= tmp_q1;
                    }
                    else
                    {
                        *ptrReal += tmp_q1;
                    }

                    if (tmp_q1)
                    {
                        tone_count++;
                    }

                    ptrReal++;

                }   /*  for-loop (k) */

            }
            else        /*  if indexPlus1 is even */
            {
                indexMinus1 = (*harm_index - 1) & 3;

                /*  ---  k = 0  ----- */

                sb_gain_man = nrg_gain_man[0];
                tmp_q1 = *ptrReal;
                tmp_q2 = nrg_gain_exp[0];
                tmp_q1 = fxp_mul32_Q28(tmp_q1, sb_gain_man);

                if (tmp_q2 < 0)
                {
                    if (tmp_q2 > -32)
                    {
                        *ptrReal = tmp_q1 >> (-tmp_q2);
                    }
                }
                else
                {
                    *ptrReal = tmp_q1 << tmp_q2;
                }

                *phase_index = (*phase_index + 1) & 511;

                tmp_q1 = nrg_tone_exp[0];
                tmp_q2 = nrg_tone_exp[1];

                if ((indexPlus1 != 0) ^((lowSubband & 1) != 0))
                {
                    *(ptrReal - 1) -= tmp_q1;
                    *(ptrReal)   += tmp_q2;
                }
                else
                {
                    *(ptrReal - 1) += tmp_q1;
                    *(ptrReal)   -= tmp_q2;
                }

                if (!nrg_tone_man[0] && !noNoiseFlag)
                {
                    tmp_16 = rP_LCx[*phase_index];
                    sb_noise_man = noise_level_man[0];
                    sb_noise_exp = noise_level_exp[0];

                    tmp_q1 = fxp_mul32_by_16(sb_noise_man, tmp_16);

                    if (sb_noise_exp < 0)
                    {
                        if (sb_noise_exp > -32)
                        {
                            *ptrReal += tmp_q1 >> (-sb_noise_exp);
                        }
                    }
                    else
                    {
                        *ptrReal += tmp_q1 << sb_noise_exp;
                    }
                }
                else
                {
                    tone_count++;
                }

                ptrReal++;

                /* ----  */

                for (k = 1; k < noSubbands - 1; k++)
                {

                    sb_gain_man = nrg_gain_man[k];
                    tmp_q1 = *ptrReal;
                    tmp_q2 = nrg_gain_exp[k];
                    tmp_q1 = fxp_mul32_Q28(tmp_q1, sb_gain_man);

                    if (tmp_q2 < 0)
                    {
                        if (tmp_q2 > -32)
                        {
                            *ptrReal = tmp_q1 >> (-tmp_q2);
                        }
                    }
                    else
                    {
                        *ptrReal = tmp_q1 << tmp_q2;
                    }

                    *phase_index = (*phase_index + 1) & 511;


                    if (tone_count < 16)
                    {
                        tmp_q1 = nrg_tone_exp[k - 1];
                        tmp_q2 = nrg_tone_exp[k + 1];

                        tmp_q1 -= tmp_q2;


                        if ((indexPlus1 != 0) ^(((k + lowSubband) & 1) != 0))
                        {
                            *(ptrReal) -= tmp_q1;
                        }
                        else
                        {
                            *(ptrReal) += tmp_q1;
                        }
                    }   /*   if (tone_count < 16)  */


                    if (!nrg_tone_man[k] && !noNoiseFlag)
                    {
                        tmp_16 = rP_LCx[*phase_index];
                        sb_noise_man = noise_level_man[k];
                        sb_noise_exp = noise_level_exp[k];

                        tmp_q1 = fxp_mul32_by_16(sb_noise_man, tmp_16);

                        if (sb_noise_exp < 0)
                        {
                            if (sb_noise_exp > -32)
                            {
                                *ptrReal += tmp_q1 >> (-sb_noise_exp);
                            }
                        }
                        else
                        {
                            *ptrReal += tmp_q1 << sb_noise_exp;
                        }
                    }
                    else
                    {
                        tone_count++;
                    }

                    ptrReal++;

                }   /*  for-loop (k) */

                sb_gain_man = nrg_gain_man[k];
                tmp_q1 = *ptrReal;
                tmp_q2 = nrg_gain_exp[k];
                tmp_q1 = fxp_mul32_Q28(tmp_q1, sb_gain_man);

                if (tmp_q2 < 0)
                {
                    if (tmp_q2 > -31)
                    {
                        *ptrReal = tmp_q1 >> (-tmp_q2);
                    }
                }
                else
                {
                    *ptrReal = tmp_q1 << tmp_q2;
                }

                *phase_index = (*phase_index + 1) & 511;


                if ((tone_count < 16) && !(indexMinus1 &1))
                {
                    tmp_q1 = nrg_tone_exp[k - 1];
                    tmp_q2 = nrg_tone_exp[k    ];

                    if ((indexMinus1 != 0) ^(((k + lowSubband) & 1) != 0))
                    {
                        *(ptrReal)   += tmp_q1;

                        if (k + lowSubband < 62)
                        {
                            *(ptrReal + 1) -= tmp_q2;
                        }
                    }
                    else
                    {
                        *(ptrReal)   -= tmp_q1;

                        if (k + lowSubband < 62)
                        {
                            *(ptrReal + 1) += tmp_q2;
                        }
                    }
                }   /*   if (tone_count < 16)  */


                if (!nrg_tone_man[k] && !noNoiseFlag)
                {
                    tmp_16 = rP_LCx[*phase_index];
                    sb_noise_man = noise_level_man[k];
                    sb_noise_exp = noise_level_exp[k];

                    tmp_q1 = fxp_mul32_by_16(sb_noise_man, tmp_16);

                    if (sb_noise_exp < 0)
                    {
                        if (sb_noise_exp > -31)
                        {
                            *ptrReal += tmp_q1 >> (-sb_noise_exp);
                        }
                    }
                    else
                    {
                        *ptrReal += tmp_q1 << sb_noise_exp;
                    }
                }

            }   /*  if indexPlus1 is odd */

            *harm_index = indexPlus1;


        }        /*  for-loop (l) */

    }
    else        /*   if ( band_nrg_tone_detector)   */
    {

        for (k = 0; k < noSubbands; k++)
        {
            tmp_q1 = noise_level_exp[k];
            tmp_q2 = nrg_gain_exp[k];
            noise_level_exp[k] =  tmp_q1 + 1;
            nrg_gain_exp[k] = tmp_q2 + 28;
        }

        for (l = (frame_info[1+i] << 1); l < (frame_info[2+i] << 1); l++)
        {
            ptrReal = (aBufR + l * SBR_NUM_BANDS);

            for (k = 0; k < noSubbands; k++)
            {

                tmp_q1 = *ptrReal;
                sb_gain_man = nrg_gain_man[k];

                tmp_q2 = nrg_gain_exp[k];

                tmp_q1 = fxp_mul32_Q28(tmp_q1, sb_gain_man);

                if (tmp_q2 < 0)
                {
                    if (tmp_q2 > -31)
                    {
                        *ptrReal = tmp_q1 >> (-tmp_q2);
                    }
                }
                else
                {
                    *ptrReal = tmp_q1 << tmp_q2;
                }

                *phase_index = (*phase_index + 1) & 511;

                if (! noNoiseFlag)
                {
                    tmp_16 = rP_LCx[*phase_index];
                    sb_noise_man = noise_level_man[k];
                    sb_noise_exp = noise_level_exp[k];

                    tmp_q1 = fxp_mul32_by_16(sb_noise_man, tmp_16);

                    if (sb_noise_exp < 0)
                    {
                        if (sb_noise_exp > -31)
                        {
                            *ptrReal += tmp_q1 >> (-sb_noise_exp);
                        }
                    }
                    else
                    {
                        *ptrReal += tmp_q1 << sb_noise_exp;
                    }
                }

                ptrReal++;

            }   /*  for-loop (k) */

            *harm_index  = (*harm_index + 1) & 3;


        }   /*  for-loop (l) */

    }

}



/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/


#define Qfmt15(a)   (Int32)(a*((Int32)1<<15) + (a>=0?0.5F:-0.5F))


const Int16 pow2[39] = { 0, 0, 1, 0, 2,
                         0, Qfmt15(2 / 6.0f), 0, 3, 0, Qfmt15(2 / 10.0f), 0, Qfmt15(2 / 12.0f), 0, Qfmt15(2 / 14.0f), 0, 4,
                         0, Qfmt15(2 / 18.0f),    0, Qfmt15(2 / 20.0f), 0, Qfmt15(2 / 22.0f), 0, Qfmt15(2 / 24.0f),
                         0, Qfmt15(2 / 26.0f), 0, Qfmt15(2 / 28.0f), 0, Qfmt15(2 / 30.0f), 0, 5, 0, Qfmt15(2 / 34.0f),
                         0, Qfmt15(2 / 36.0f), 0, Qfmt15(2 / 38.0f)
                       };

void energy_estimation_LC(Int32 *aBufR,
                          Int32 *nrg_est_man,
                          Int32 *nrg_est_exp,
                          const Int32 *frame_info,
                          Int32 i,
                          Int32 k,
                          Int32 c,
                          Int32 ui2)
{


    Int32  aux1;
    Int32  aux2;
    Int32  l;


    int64_t nrg_h = 0;
    Int32 tmp1;
    UInt32 tmp2;

    for (l = ui2; l < (frame_info[2+i] << 1); l++)
    {

        aux1 = aBufR[l++*SBR_NUM_BANDS + k ];
        aux2 = aBufR[l  *SBR_NUM_BANDS + k ];

        nrg_h = fxp_mac64_Q31(nrg_h, aux1, aux1);
        nrg_h = fxp_mac64_Q31(nrg_h, aux2, aux2);
    }

    /*
     *  Check for overflow and saturate if needed
     */
    if (nrg_h < 0)
    {
        nrg_h = 0x7fffffff;
    }


    if (nrg_h)
    {
        tmp2 = (UInt32)(nrg_h >> 32);
        if (tmp2)
        {
            aux2 = pv_normalize(tmp2);
            aux2 -= 1;                  /*  ensure Q30 */
            nrg_h = (nrg_h << aux2) >> 33;
            tmp2 = (UInt32)(nrg_h);
            nrg_est_exp[c] = 33 - aux2;
        }
        else
        {
            tmp2 = (UInt32)(nrg_h >> 2);
            aux2 = pv_normalize(tmp2);
            aux2 -= 1;                  /*  ensure Q30 */

            tmp2 = (tmp2 << aux2);
            nrg_est_exp[c] =  -aux2 + 2;
        }

        tmp1 = (l - ui2);

        aux2 = pow2[tmp1];
        if (tmp1 == (tmp1 & (-tmp1)))
        {
            nrg_est_man[c] = tmp2 >> aux2;
        }
        else
        {
            nrg_est_man[c] = fxp_mul32_by_16(tmp2, aux2);
        }

    }
    else
    {
        nrg_est_man[c] = 0;
        nrg_est_exp[c] = -100;
    }





}






#if HQ_SBR

/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

void envelope_application(Int32  *aBufR,
                          Int32  *aBufI,
                          Int32  *nrg_gain_man,
                          Int32  *nrg_gain_exp,
                          Int32  *noise_level_man,
                          Int32  *noise_level_exp,
                          Int32  *nrg_tone_man,
                          Int32  *nrg_tone_exp,
                          Int32  *fBuf_man[64],
                          Int32  *fBuf_exp[64],
                          Int32  *fBufN_man[64],
                          Int32  *fBufN_exp[64],
                          const  Int32 *frame_info,
                          Int32  *harm_index,
                          Int32  *phase_index,
                          Int32  i,
                          Int32  lowSubband,
                          Int32  noSubbands,
                          Int32  noNoiseFlag,
                          Int32  band_nrg_tone_detector,
                          Int32  maxSmoothLength,
                          Int32  smooth_length)
{

    Int32 *ptrReal;
    Int32 *ptrImag;
    Int32 sb_gain_man;
    Int32 sb_gain_exp;
    Int32 sb_noise_man;
    Int32 sb_noise_exp;
    Int32 l;
    Int32 k;
    Int32 n;
    Int32 tmp_q1;
    Int32 tmp_q2;
    Int32  aux1;
    Int32  aux2;
    Int32  filter_history = 0;


    if (band_nrg_tone_detector)     /* Add tone energy only if energy is detected  */
    {

        /*
         *  pre-calculate tone application
         */

        ptrReal = nrg_tone_exp;
        ptrImag = nrg_tone_man;
        tmp_q1 = - *(ptrReal++);
        aux1   =   *(ptrImag);
        for (k = 0; k < noSubbands; k++)
        {
            *(ptrImag++) = aux1 >> tmp_q1;
            tmp_q1 = - *(ptrReal++);
            aux1   =   *(ptrImag);
        }

        /*
         *          Application
         */

        for (l = (frame_info[1+i] << 1); l < (frame_info[2+i] << 1); l++)
        {
            ptrReal = (aBufR + l * SBR_NUM_BANDS);
            ptrImag = (aBufI + l * SBR_NUM_BANDS);

            if (filter_history <= maxSmoothLength)     /* no more update is needed as buffer will have same info */
            {
                pv_memmove(fBuf_man[maxSmoothLength], nrg_gain_man, noSubbands*sizeof(*nrg_gain_man));
                pv_memmove(fBuf_exp[maxSmoothLength], nrg_gain_exp, noSubbands*sizeof(*nrg_gain_exp));
                pv_memmove(fBufN_man[maxSmoothLength], noise_level_man, noSubbands*sizeof(*noise_level_man));
                pv_memmove(fBufN_exp[maxSmoothLength], noise_level_exp, noSubbands*sizeof(*noise_level_exp));
            }

            /*
             *  nrg_gain_max bounded to 1.584893192*1e5, which requires (32-bit) Q14 notation
             */
            for (k = 0; k < noSubbands; k++)
            {
                if (smooth_length == 0)     /* no filter-smooth needed */
                {
                    sb_gain_man = nrg_gain_man[k];
                    sb_gain_exp = nrg_gain_exp[k];

                    sb_noise_man = noise_level_man[k];
                    sb_noise_exp = noise_level_exp[k];

                }
                else
                {   /* else  smooth_length == 4  and fir_4 filter is being used */

                    sb_gain_exp = fBuf_exp[maxSmoothLength][k];

                    sb_noise_exp = fBufN_exp[maxSmoothLength][k];

                    for (n = maxSmoothLength - smooth_length; n < maxSmoothLength; n++)
                    {
                        if (sb_gain_exp  < fBuf_exp[n][k])
                        {
                            sb_gain_exp = fBuf_exp[n][k];
                        }

                        if (sb_noise_exp  < fBufN_exp[n][k])
                        {
                            sb_noise_exp = fBufN_exp[n][k];
                        }
                    }

                    sb_gain_man = fxp_mul32_Q30(fBuf_man[maxSmoothLength][k], Q30fmt(0.33333333333333f));
                    sb_gain_man  = sb_gain_man >> (sb_gain_exp - fBuf_exp[maxSmoothLength][k]);

                    sb_noise_man = fxp_mul32_Q30(fBufN_man[maxSmoothLength][k], Q30fmt(0.33333333333333f));
                    sb_noise_man = sb_noise_man >> (sb_noise_exp - fBufN_exp[maxSmoothLength][k]);

                    n = maxSmoothLength - smooth_length;

                    tmp_q1 = fxp_mul32_Q30(fBuf_man[n][k], Q30fmt(0.03183050093751f));
                    sb_gain_man  += tmp_q1 >> (sb_gain_exp - fBuf_exp[n][k]);

                    tmp_q1 = fxp_mul32_Q30(fBufN_man[n][k], Q30fmt(0.03183050093751f));
                    sb_noise_man += tmp_q1 >> (sb_noise_exp - fBufN_exp[n++][k]);

                    tmp_q1 = fxp_mul32_Q30(fBuf_man[n][k], Q30fmt(0.11516383427084f));
                    sb_gain_man  += tmp_q1 >> (sb_gain_exp - fBuf_exp[n][k]);

                    tmp_q1 = fxp_mul32_Q30(fBufN_man[n][k], Q30fmt(0.11516383427084f));
                    sb_noise_man += tmp_q1 >> (sb_noise_exp - fBufN_exp[n++][k]);

                    tmp_q1 = fxp_mul32_Q30(fBuf_man[n][k], Q30fmt(0.21816949906249f));
                    sb_gain_man  += tmp_q1 >> (sb_gain_exp - fBuf_exp[n][k]);

                    tmp_q1 = fxp_mul32_Q30(fBufN_man[n][k], Q30fmt(0.21816949906249f));
                    sb_noise_man += tmp_q1 >> (sb_noise_exp - fBufN_exp[n++][k]);

                    tmp_q1 = fxp_mul32_Q30(fBuf_man[n][k], Q30fmt(0.30150283239582f));
                    sb_gain_man  += tmp_q1 >> (sb_gain_exp - fBuf_exp[n][k]);

                    tmp_q1 = fxp_mul32_Q30(fBufN_man[n][k], Q30fmt(0.30150283239582f));
                    sb_noise_man += tmp_q1 >> (sb_noise_exp - fBufN_exp[n][k]);

                }



                /*
                 *    *ptrReal  = *ptrReal * sb_gain ;
                 *    *ptrImag  = *ptrImag * sb_gain;
                 */
                aux1 = *ptrReal;
                aux2 = *ptrImag;
                sb_gain_exp += 32;
                aux1 = fxp_mul32_Q31(aux1, sb_gain_man);
                aux2 = fxp_mul32_Q31(aux2, sb_gain_man);


                if (sb_gain_exp < 0)
                {
                    sb_gain_exp = -sb_gain_exp;
                    if (sb_gain_exp < 32)
                    {
                        *ptrReal = (aux1 >> sb_gain_exp);
                        *ptrImag = (aux2 >> sb_gain_exp);
                    }
                }
                else
                {
                    *ptrReal = (aux1 << sb_gain_exp);
                    *ptrImag = (aux2 << sb_gain_exp);
                }



                /*
                 *     if ( sb_noise != 0)
                 *     {
                 *         *ptrReal += sb_noise * rP[*phase_index][0];
                 *         *ptrImag += sb_noise * rP[*phase_index][1];
                 *     }
                 */
                *phase_index = (*phase_index + 1) & 511;

                if (nrg_tone_man[k] || noNoiseFlag)
                {
                    sb_noise_man = 0;
                    sb_noise_exp = 0;
                }
                else
                {

                    Int32 tmp = rPxx[*phase_index];
                    sb_noise_exp += 1;
                    tmp_q1 = fxp_mul32_by_16t(sb_noise_man, tmp);
                    tmp_q2 = fxp_mul32_by_16b(sb_noise_man, tmp);


                    if (sb_noise_exp < 0)
                    {
                        if (sb_noise_exp > -32)
                        {
                            *ptrReal += tmp_q1 >> (-sb_noise_exp);
                            *ptrImag += tmp_q2 >> (-sb_noise_exp);
                        }
                    }
                    else
                    {
                        *ptrReal += tmp_q1 << sb_noise_exp;
                        *ptrImag += tmp_q2 << sb_noise_exp;
                    }
                }

                /*
                 *      tmp_q1 = nrg_tone[k]
                 */

                tmp_q1 = nrg_tone_man[k];

                if (*harm_index & 1)
                {
                    if ((((k + lowSubband) & 1) != 0) ^(*harm_index != 1))
                    {
                        *ptrImag  -=  tmp_q1;
                    }
                    else
                    {
                        *ptrImag  +=  tmp_q1;
                    }
                }
                else
                {
                    *ptrReal += (*harm_index) ? -tmp_q1 : tmp_q1;
                }

                *ptrReal++ <<= 10;
                *ptrImag++ <<= 10;


            }    /*  for-loop (k) */


            *harm_index = (*harm_index + 1) & 3;

            /*
             *  Update smoothing filter history
             */

            if (filter_history++ < maxSmoothLength)     /* no more update is needed as buffer will have same info */
            {
                /*
                 *  mantissas
                 */

                ptrReal = (Int32 *)fBuf_man[0];
                ptrImag = (Int32 *)fBufN_man[0];

                for (n = 0; n < maxSmoothLength; n++)
                {
                    fBuf_man[n]  = fBuf_man[n+1];
                    fBufN_man[n] = fBufN_man[n+1];
                }

                fBuf_man[maxSmoothLength]  = ptrReal;
                fBufN_man[maxSmoothLength] = ptrImag;

                /*
                 *  exponents
                 */
                ptrReal = (Int32 *)fBuf_exp[0];
                ptrImag = (Int32 *)fBufN_exp[0];

                for (n = 0; n < maxSmoothLength; n++)
                {
                    fBuf_exp[n]  = fBuf_exp[n+1];
                    fBufN_exp[n] = fBufN_exp[n+1];
                }

                fBuf_exp[maxSmoothLength]  = ptrReal;
                fBufN_exp[maxSmoothLength] = ptrImag;
            }

        }   /*  for-loop (l) */


    }
    else        /*   ----  if ( band_nrg_tone_detector) ---- */
    {

        /*
         *          Application
         */

        for (l = (frame_info[1+i] << 1); l < (frame_info[2+i] << 1); l++)
        {
            ptrReal = (aBufR + l * SBR_NUM_BANDS);
            ptrImag = (aBufI + l * SBR_NUM_BANDS);

            if (filter_history <= maxSmoothLength)     /* no more update is needed as buffer will have same info */
            {
                pv_memmove(fBuf_man[maxSmoothLength], nrg_gain_man, noSubbands*sizeof(*nrg_gain_man));
                pv_memmove(fBuf_exp[maxSmoothLength], nrg_gain_exp, noSubbands*sizeof(*nrg_gain_exp));
                pv_memmove(fBufN_man[maxSmoothLength], noise_level_man, noSubbands*sizeof(*noise_level_man));
                pv_memmove(fBufN_exp[maxSmoothLength], noise_level_exp, noSubbands*sizeof(*noise_level_exp));
            }

            /*
             *  nrg_gain_max bounded to 1.584893192*1e5, which requires (32-bit) Q14 notation
             */
            for (k = 0; k < noSubbands; k++)
            {
                if (smooth_length == 0)     /* no filter-smooth needed */
                {
                    sb_gain_man = nrg_gain_man[k];
                    sb_gain_exp = nrg_gain_exp[k];

                    sb_noise_man = noise_level_man[k];
                    sb_noise_exp = noise_level_exp[k];

                }
                else
                {   /* else  smooth_length == 4  and fir_4 filter is being used */

                    sb_gain_exp = fBuf_exp[maxSmoothLength][k];

                    sb_noise_exp = fBufN_exp[maxSmoothLength][k];

                    for (n = maxSmoothLength - smooth_length; n < maxSmoothLength; n++)
                    {
                        if (sb_gain_exp  < fBuf_exp[n][k])
                        {
                            sb_gain_exp = fBuf_exp[n][k];
                        }

                        if (sb_noise_exp  < fBufN_exp[n][k])
                        {
                            sb_noise_exp = fBufN_exp[n][k];
                        }
                    }

                    sb_gain_man = fxp_mul32_Q30(fBuf_man[maxSmoothLength][k], Q30fmt(0.33333333333333f));
                    sb_gain_man  = sb_gain_man >> (sb_gain_exp - fBuf_exp[maxSmoothLength][k]);

                    sb_noise_man = fxp_mul32_Q30(fBufN_man[maxSmoothLength][k], Q30fmt(0.33333333333333f));
                    sb_noise_man = sb_noise_man >> (sb_noise_exp - fBufN_exp[maxSmoothLength][k]);

                    n = maxSmoothLength - smooth_length;

                    tmp_q1 = fxp_mul32_Q30(fBuf_man[n][k], Q30fmt(0.03183050093751f));
                    sb_gain_man  += tmp_q1 >> (sb_gain_exp - fBuf_exp[n][k]);

                    tmp_q1 = fxp_mul32_Q30(fBufN_man[n][k], Q30fmt(0.03183050093751f));
                    sb_noise_man += tmp_q1 >> (sb_noise_exp - fBufN_exp[n++][k]);

                    tmp_q1 = fxp_mul32_Q30(fBuf_man[n][k], Q30fmt(0.11516383427084f));
                    sb_gain_man  += tmp_q1 >> (sb_gain_exp - fBuf_exp[n][k]);

                    tmp_q1 = fxp_mul32_Q30(fBufN_man[n][k], Q30fmt(0.11516383427084f));
                    sb_noise_man += tmp_q1 >> (sb_noise_exp - fBufN_exp[n++][k]);

                    tmp_q1 = fxp_mul32_Q30(fBuf_man[n][k], Q30fmt(0.21816949906249f));
                    sb_gain_man  += tmp_q1 >> (sb_gain_exp - fBuf_exp[n][k]);

                    tmp_q1 = fxp_mul32_Q30(fBufN_man[n][k], Q30fmt(0.21816949906249f));
                    sb_noise_man += tmp_q1 >> (sb_noise_exp - fBufN_exp[n++][k]);

                    tmp_q1 = fxp_mul32_Q30(fBuf_man[n][k], Q30fmt(0.30150283239582f));
                    sb_gain_man  += tmp_q1 >> (sb_gain_exp - fBuf_exp[n][k]);

                    tmp_q1 = fxp_mul32_Q30(fBufN_man[n][k], Q30fmt(0.30150283239582f));
                    sb_noise_man += tmp_q1 >> (sb_noise_exp - fBufN_exp[n][k]);

                }



                /*
                 *    *ptrReal  = *ptrReal * sb_gain ;
                 *    *ptrImag  = *ptrImag * sb_gain;
                 */
                aux1 = *ptrReal;
                aux2 = *ptrImag;
                sb_gain_exp += 32;
                aux1 = fxp_mul32_Q31(aux1, sb_gain_man);
                aux2 = fxp_mul32_Q31(aux2, sb_gain_man);



                /*
                 *     if ( sb_noise != 0)
                 *     {
                 *         *ptrReal += sb_noise * rP[*phase_index][0];
                 *         *ptrImag += sb_noise * rP[*phase_index][1];
                 *     }
                 */


                if (sb_gain_exp < 0)
                {
                    if (sb_gain_exp > -32)
                    {
                        if (sb_gain_exp > -10)
                        {
                            *ptrReal = aux1 << (10 + sb_gain_exp);
                            *ptrImag = aux2 << (10 + sb_gain_exp);
                        }
                        else
                        {
                            *ptrReal = aux1 >> (-sb_gain_exp - 10);
                            *ptrImag = aux2 >> (-sb_gain_exp - 10);
                        }
                    }
                }
                else
                {
                    *ptrReal = aux1 << (sb_gain_exp + 10);
                    *ptrImag = aux2 << (sb_gain_exp + 10);
                }




                /*
                 *     if ( sb_noise != 0)
                 *     {
                 *         *ptrReal += sb_noise * rP[*phase_index][0];
                 *         *ptrImag += sb_noise * rP[*phase_index][1];
                 *     }
                 */
                *phase_index = (*phase_index + 1) & 511;

                if (!noNoiseFlag)
                {

                    Int32 tmp = rPxx[*phase_index];
                    sb_noise_exp += 1;
                    tmp_q1 = fxp_mul32_by_16t(sb_noise_man, tmp);
                    tmp_q2 = fxp_mul32_by_16b(sb_noise_man, tmp);

                    if (sb_noise_exp < 0)
                    {
                        if (sb_noise_exp > -32)
                        {
                            if (sb_noise_exp > -10)
                            {
                                *ptrReal += tmp_q1 << (10 + sb_noise_exp);
                                *ptrImag += tmp_q2 << (10 + sb_noise_exp);
                            }
                            else
                            {
                                *ptrReal += tmp_q1 >> (-sb_noise_exp - 10);
                                *ptrImag += tmp_q2 >> (-sb_noise_exp - 10);
                            }
                        }
                    }
                    else
                    {
                        *ptrReal += tmp_q1 << (sb_noise_exp + 10);
                        *ptrImag += tmp_q2 << (sb_noise_exp + 10);
                    }
                }

                ptrReal++;
                ptrImag++;


            }    /*  for-loop (k) */


            *harm_index = (*harm_index + 1) & 3;

            /*
             *  Update smoothing filter history
             */

            if (filter_history++ < maxSmoothLength)     /* no more update is needed as buffer will have same info */
            {
                /*
                 *  mantissas
                 */

                ptrReal = (Int32 *)fBuf_man[0];
                ptrImag = (Int32 *)fBufN_man[0];

                for (n = 0; n < maxSmoothLength; n++)
                {
                    fBuf_man[n]  = fBuf_man[n+1];
                    fBufN_man[n] = fBufN_man[n+1];
                }

                fBuf_man[maxSmoothLength]  = ptrReal;
                fBufN_man[maxSmoothLength] = ptrImag;

                /*
                 *  exponents
                 */
                ptrReal = (Int32 *)fBuf_exp[0];
                ptrImag = (Int32 *)fBufN_exp[0];

                for (n = 0; n < maxSmoothLength; n++)
                {
                    fBuf_exp[n]  = fBuf_exp[n+1];
                    fBufN_exp[n] = fBufN_exp[n+1];
                }

                fBuf_exp[maxSmoothLength]  = ptrReal;
                fBufN_exp[maxSmoothLength] = ptrImag;
            }

        }   /*  for-loop (l) */

    }       /*  if ( band_nrg_tone_detector) */

}


/*----------------------------------------------------------------------------
; FUNCTION CODE
----------------------------------------------------------------------------*/

void energy_estimation(Int32 *aBufR,
                       Int32 *aBufI,
                       Int32 *nrg_est_man,
                       Int32 *nrg_est_exp,
                       const Int32 *frame_info,
                       Int32 i,
                       Int32 k,
                       Int32 c,
                       Int32 ui2)
{

    Int32  aux1;
    Int32  aux2;
    Int32  l;



    int64_t nrg_h = 0;
    Int32 tmp1;
    Int32 tmp2;

    aux1 = aBufR[ui2*SBR_NUM_BANDS + k];
    aux2 = aBufI[ui2*SBR_NUM_BANDS + k];
    for (l = ui2 + 1; l < (frame_info[2+i] << 1);  l++)
    {
        nrg_h = fxp_mac64_Q31(nrg_h, aux1, aux1);
        nrg_h = fxp_mac64_Q31(nrg_h, aux2, aux2);
        aux1 = aBufR[l*SBR_NUM_BANDS + k];
        aux2 = aBufI[l*SBR_NUM_BANDS + k];
    }
    nrg_h = fxp_mac64_Q31(nrg_h, aux1, aux1);
    nrg_h = fxp_mac64_Q31(nrg_h, aux2, aux2);


    /*
     *  Check for overflow and saturate if needed
     */
    if (nrg_h < 0)
    {
        nrg_h = 0x7fffffff;
    }

    if (nrg_h)
    {

        aux1 = (UInt32)(nrg_h >> 32);
        if (aux1)
        {
            aux2 = pv_normalize(aux1);
            if (aux2)
            {
                aux2 -= 1;                  /*  ensure Q30 */
                nrg_h = (nrg_h << aux2) >> 33;
                tmp2 = (UInt32)(nrg_h);
                nrg_est_exp[c] = 33 - aux2;
            }
            else
            {
                tmp2 = (UInt32)(aux1 >> 1);
                nrg_est_exp[c] = 33 ;


            }
        }
        else
        {
            aux1 = (UInt32)(nrg_h >> 1);
            aux2 = pv_normalize(aux1);

            tmp2 = (aux1 << aux2);
            nrg_est_exp[c] =  -aux2 + 1;


        }



        tmp1 = (l - ui2);
        aux2 = pow2[tmp1];
        if (tmp1 == (tmp1 & (-tmp1)))
        {
            nrg_est_man[c] = tmp2 >> aux2;
        }
        else
        {
            nrg_est_man[c] = fxp_mul32_by_16(tmp2, aux2);
        }
    }
    else
    {
        nrg_est_man[c] = 0;
        nrg_est_exp[c] = -100;
    }


}





#endif


#endif



