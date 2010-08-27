/*
 * Copyright (C) 2004-2010 NXP Software
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef __LVCS_TABLES_H__
#define __LVCS_TABLES_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

/************************************************************************************/
/*                                                                                  */
/*  Includes                                                                        */
/*                                                                                  */
/************************************************************************************/

#include "BIQUAD.h"                             /* Biquad definitions */

/************************************************************************************/
/*                                                                                  */
/*  Stereo Enhancer coefficient constant tables                                     */
/*                                                                                  */
/************************************************************************************/

/* Coefficient table for the middle filter */
extern const BiquadA01B1CoefsSP_t LVCS_SEMidCoefTable[];

/* Coefficient table for the side filter */
extern const BiquadA012B12CoefsSP_t LVCS_SESideCoefTable[];

/************************************************************************************/
/*                                                                                  */
/*  Equaliser coefficient constant tables                                           */
/*                                                                                  */
/************************************************************************************/

extern const BiquadA012B12CoefsSP_t LVCS_EqualiserCoefTable[];

/************************************************************************************/
/*                                                                                  */
/*  Reverb delay constant tables                                                    */
/*                                                                                  */
/************************************************************************************/

/* Stereo delay table for Concert Sound */
extern const LVM_UINT16 LVCS_StereoDelayCS[];

/************************************************************************************/
/*                                                                                  */
/*  Reverb coefficients constant table                                              */
/*                                                                                  */
/************************************************************************************/

extern const BiquadA012B12CoefsSP_t LVCS_ReverbCoefTable[];

/************************************************************************************/
/*                                                                                  */
/*  Bypass mixer constant tables                                                    */
/*                                                                                  */
/************************************************************************************/

extern const Gain_t LVCS_OutputGainTable[];

/************************************************************************************/
/*                                                                                  */
/*  Volume correction table                                                         */
/*                                                                                  */
/*  Coefficient order:                                                              */
/*      Compression 100% effect                                                     */
/*      Compression 0% effect                                                       */
/*      Gain 100% effect                                                            */
/*      Gain 0% effect                                                              */
/*                                                                                  */
/*  The Compression gain is represented by a Q1.15 number to give a range of 0dB    */
/*  to +6dB, E.g.:                                                                  */
/*          0       is 0dB compression (no effect)                                  */
/*          5461    is 1dB compression gain                                         */
/*          10923   is 2dB compression gain                                         */
/*          32767   is 6dB compression gain                                         */
/*                                                                                  */
/*  The Gain is represented as a Q3.13 number to give a range of +8 to -infinity    */
/*  E.g.:                                                                           */
/*          0       is -infinity                                                    */
/*          32767   is +18dB (x8) gain                                              */
/*          4096    is 0dB gain                                                     */
/*          1024    is -12dB gain                                                   */
/*                                                                                  */
/************************************************************************************/

extern const LVCS_VolCorrect_t LVCS_VolCorrectTable[];
extern const LVM_INT16 LVCS_VolumeTCTable[];


/************************************************************************************/
/*                                                                                  */
/*  Sample rates                                                                    */
/*                                                                                  */
/************************************************************************************/

extern LVM_INT32                LVCS_SampleRateTable[];


/*Speaker coeffient tables*/
extern LVM_UINT16               LVCS_MS_Small_SEMiddleGainTable[];
extern BiquadA012B12CoefsSP_t   LVCS_MS_Small_SESideCoefTable[];
extern BiquadA012B12CoefsSP_t   LVCS_MS_Small_EqualiserCoefTable[];
extern BiquadA012B12CoefsSP_t   LVCS_MS_Small_ReverbCoefTable[] ;
extern LVM_UINT16               LVCS_MS_Small_StereoDelayCS4MS[];
extern Gain_t                   LVCS_MS_Small_OutputGainTable[];
extern LVCS_VolCorrect_t        LVCS_MS_Small_VolCorrectTable[];
extern LVM_UINT16               LVCS_MS_Small_ReverbGainTable[];

extern LVM_UINT16               LVCS_MS_Medium_SEMiddleGainTable[];
extern BiquadA012B12CoefsSP_t   LVCS_MS_Medium_SESideCoefTable[];
extern BiquadA012B12CoefsSP_t   LVCS_MS_Medium_EqualiserCoefTable[];
extern BiquadA012B12CoefsSP_t   LVCS_MS_Medium_ReverbCoefTable[] ;
extern LVM_UINT16               LVCS_MS_Medium_StereoDelayCS4MS[];
extern Gain_t                   LVCS_MS_Medium_OutputGainTable[];
extern LVCS_VolCorrect_t        LVCS_MS_Medium_VolCorrectTable[];
extern LVM_UINT16               LVCS_MS_Medium_ReverbGainTable[];

extern LVM_UINT16               LVCS_MS_Large_SEMiddleGainTable[];
extern BiquadA012B12CoefsSP_t   LVCS_MS_Large_SESideCoefTable[];
extern BiquadA012B12CoefsSP_t   LVCS_MS_Large_EqualiserCoefTable[];
extern BiquadA012B12CoefsSP_t   LVCS_MS_Large_ReverbCoefTable[] ;
extern LVM_UINT16               LVCS_MS_Large_StereoDelayCS4MS[];
extern Gain_t                   LVCS_MS_Large_OutputGainTable[];
extern LVCS_VolCorrect_t        LVCS_MS_Large_VolCorrectTable[];
extern LVM_UINT16               LVCS_MS_Large_ReverbGainTable[];



#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif /* __LVCS_TABLES_H__ */

