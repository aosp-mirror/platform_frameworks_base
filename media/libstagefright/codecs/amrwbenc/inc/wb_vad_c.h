/*
 ** Copyright 2003-2010, VisualOn, Inc.
 **
 ** Licensed under the Apache License, Version 2.0 (the "License");
 ** you may not use this file except in compliance with the License.
 ** You may obtain a copy of the License at
 **
 **     http://www.apache.org/licenses/LICENSE-2.0
 **
 ** Unless required by applicable law or agreed to in writing, software
 ** distributed under the License is distributed on an "AS IS" BASIS,
 ** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 ** See the License for the specific language governing permissions and
 ** limitations under the License.
 */


/*-------------------------------------------------------------------*
 *                         WB_VAD_C.H				     *
 *-------------------------------------------------------------------*
 * Constants for Voice Activity Detection.			     *
 *-------------------------------------------------------------------*/

#ifndef __WB_VAD_C_H__
#define __WB_VAD_C_H__

#define FRAME_LEN 256                      /* Length (samples) of the input frame          */
#define COMPLEN 12                         /* Number of sub-bands used by VAD              */

#define UNIRSHFT 7                         /* = log2(MAX_16/UNITY), UNITY = 256      */
#define SCALE 128                          /* (UNITY*UNITY)/512 */

#define TONE_THR (Word16)(0.65*MAX_16)     /* Threshold for tone detection   */

/* constants for speech level estimation */
#define SP_EST_COUNT 80
#define SP_ACTIVITY_COUNT 25
#define ALPHA_SP_UP (Word16)((1.0 - 0.85)*MAX_16)
#define ALPHA_SP_DOWN (Word16)((1.0 - 0.85)*MAX_16)

#define NOM_LEVEL 2050                     /* about -26 dBov Q15 */
#define SPEECH_LEVEL_INIT NOM_LEVEL        /* initial speech level */
#define MIN_SPEECH_LEVEL1  (Word16)(NOM_LEVEL * 0.063)  /* NOM_LEVEL -24 dB */
#define MIN_SPEECH_LEVEL2  (Word16)(NOM_LEVEL * 0.2)    /* NOM_LEVEL -14 dB */
#define MIN_SPEECH_SNR 4096                /* 0 dB, lowest SNR estimation, Q12 */

/* Time constants for background spectrum update */
#define ALPHA_UP1   (Word16)((1.0 - 0.95)*MAX_16)       /* Normal update, upwards:   */
#define ALPHA_DOWN1 (Word16)((1.0 - 0.936)*MAX_16)      /* Normal update, downwards  */
#define ALPHA_UP2   (Word16)((1.0 - 0.985)*MAX_16)      /* Forced update, upwards    */
#define ALPHA_DOWN2 (Word16)((1.0 - 0.943)*MAX_16)      /* Forced update, downwards  */
#define ALPHA3      (Word16)((1.0 - 0.95)*MAX_16)       /* Update downwards          */
#define ALPHA4      (Word16)((1.0 - 0.9)*MAX_16)        /* For stationary estimation */
#define ALPHA5      (Word16)((1.0 - 0.5)*MAX_16)        /* For stationary estimation */

/* Constants for VAD threshold */
#define THR_MIN  (Word16)(1.6*SCALE)       /* Minimum threshold               */
#define THR_HIGH (Word16)(6*SCALE)         /* Highest threshold               */
#define THR_LOW (Word16)(1.7*SCALE)        /* Lowest threshold               */
#define NO_P1 31744                        /* ilog2(1), Noise level for highest threshold */
#define NO_P2 19786                        /* ilog2(0.1*MAX_16), Noise level for lowest threshold */
#define NO_SLOPE (Word16)(MAX_16*(float)(THR_LOW-THR_HIGH)/(float)(NO_P2-NO_P1))

#define SP_CH_MIN (Word16)(-0.75*SCALE)
#define SP_CH_MAX (Word16)(0.75*SCALE)
#define SP_P1 22527                        /* ilog2(NOM_LEVEL/4) */
#define SP_P2 17832                        /* ilog2(NOM_LEVEL*4) */
#define SP_SLOPE (Word16)(MAX_16*(float)(SP_CH_MAX-SP_CH_MIN)/(float)(SP_P2-SP_P1))

/* Constants for hangover length */
#define HANG_HIGH  12                      /* longest hangover               */
#define HANG_LOW  2                        /* shortest hangover               */
#define HANG_P1 THR_LOW                    /* threshold for longest hangover */
#define HANG_P2 (Word16)(4*SCALE)          /* threshold for shortest hangover */
#define HANG_SLOPE (Word16)(MAX_16*(float)(HANG_LOW-HANG_HIGH)/(float)(HANG_P2-HANG_P1))

/* Constants for burst length */
#define BURST_HIGH 8                       /* longest burst length         */
#define BURST_LOW 3                        /* shortest burst length        */
#define BURST_P1 THR_HIGH                  /* threshold for longest burst */
#define BURST_P2 THR_LOW                   /* threshold for shortest burst */
#define BURST_SLOPE (Word16)(MAX_16*(float)(BURST_LOW-BURST_HIGH)/(float)(BURST_P2-BURST_P1))

/* Parameters for background spectrum recovery function */
#define STAT_COUNT 20                      /* threshold of stationary detection counter         */

#define STAT_THR_LEVEL 184                 /* Threshold level for stationarity detection        */
#define STAT_THR 1000                      /* Threshold for stationarity detection              */

/* Limits for background noise estimate */
#define NOISE_MIN 40                       /* minimum */
#define NOISE_MAX 20000                    /* maximum */
#define NOISE_INIT 150                     /* initial */

/* Thresholds for signal power (now calculated on 2 frames) */
#define VAD_POW_LOW (Word32)30000L         /* If input power is lower than this, VAD is set to 0 */
#define POW_TONE_THR (Word32)686080L       /* If input power is lower,tone detection flag is ignored */

/* Constants for the filter bank */
#define COEFF3   13363                     /* coefficient for the 3rd order filter     */
#define COEFF5_1 21955                     /* 1st coefficient the for 5th order filter */
#define COEFF5_2 6390                      /* 2nd coefficient the for 5th order filter */
#define F_5TH_CNT 5                        /* number of 5th order filters */
#define F_3TH_CNT 6                        /* number of 3th order filters */

#endif   //__WB_VAD_C_H__



