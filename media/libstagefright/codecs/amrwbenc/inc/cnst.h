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


/*--------------------------------------------------------------------------*
 *                         CNST.H                                           *
 *--------------------------------------------------------------------------*
 *       Codec constant parameters (coder and decoder)                      *
 *--------------------------------------------------------------------------*/

#ifndef __CNST_H__
#define __CNST_H__

#define L_FRAME16k   320                   /* Frame size at 16kHz                        */
#define L_FRAME      256                   /* Frame size                                 */
#define L_SUBFR16k   80                    /* Subframe size at 16kHz                     */

#define L_SUBFR      64                    /* Subframe size                              */
#define NB_SUBFR     4                     /* Number of subframe per frame               */

#define L_NEXT       64                    /* Overhead in LP analysis                    */
#define L_WINDOW     384                   /* window size in LP analysis                 */
#define L_TOTAL      384                   /* Total size of speech buffer.               */
#define M            16                    /* Order of LP filter                         */
#define M16k         20

#define L_FILT16k    15                    /* Delay of down-sampling filter              */
#define L_FILT       12                    /* Delay of up-sampling filter                */

#define GP_CLIP      15565                 /* Pitch gain clipping = 0.95 Q14             */
#define PIT_SHARP    27853                 /* pitch sharpening factor = 0.85 Q15         */

#define PIT_MIN      34                    /* Minimum pitch lag with resolution 1/4      */
#define PIT_FR2      128                   /* Minimum pitch lag with resolution 1/2      */
#define PIT_FR1_9b   160                   /* Minimum pitch lag with resolution 1        */
#define PIT_FR1_8b   92                    /* Minimum pitch lag with resolution 1        */
#define PIT_MAX      231                   /* Maximum pitch lag                          */
#define L_INTERPOL   (16+1)                /* Length of filter for interpolation         */

#define OPL_DECIM    2                     /* Decimation in open-loop pitch analysis     */

#define PREEMPH_FAC  22282                 /* preemphasis factor (0.68 in Q15)           */
#define GAMMA1       30147                 /* Weighting factor (numerator) (0.92 in Q15) */
#define TILT_FAC     22282                 /* tilt factor (denominator) (0.68 in Q15)    */

#define Q_MAX        8                     /* scaling max for signal (see syn_filt_32)   */

#define RANDOM_INITSEED  21845             /* own random init value                      */

#define L_MEANBUF        3
#define ONE_PER_MEANBUF 10923

#define MODE_7k       0
#define MODE_9k       1
#define MODE_12k      2
#define MODE_14k      3
#define MODE_16k      4
#define MODE_18k      5
#define MODE_20k      6
#define MODE_23k      7
#define MODE_24k      8
#define MRDTX         9
#define NUM_OF_MODES  10                   /* see bits.h for bits definition             */

#define EHF_MASK (Word16)0x0008            /* homing frame pattern                       */

#endif //__CNST_H__

