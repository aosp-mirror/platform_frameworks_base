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


/************************************************************************************/
/*                                                                                  */
/*    Includes                                                                      */
/*                                                                                  */
/************************************************************************************/
#ifndef __LVBDE_TABLES_H__
#define __LVBDE_TABLES_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

#include "BIQUAD.h"
#include "LVM_Types.h"

/************************************************************************************/
/*                                                                                  */
/*    Coefficients constant table                                                   */
/*                                                                                  */
/************************************************************************************/

/*
 * High Pass Filter Coefficient table
 */
extern const BQ_C32_Coefs_t LVDBE_HPF_Table[];

/*
 * Band Pass Filter coefficient table
 */
extern const BP_C32_Coefs_t LVDBE_BPF_Table[];

/************************************************************************************/
/*                                                                                  */
/*    AGC constant tables                                                           */
/*                                                                                  */
/************************************************************************************/

/* Attack time (signal too large) */
extern const LVM_INT16 LVDBE_AGC_ATTACK_Table[];

/* Decay time (signal too small) */
extern const LVM_INT16 LVDBE_AGC_DECAY_Table[];

/* Gain for use without the high pass filter */
extern const LVM_INT32 LVDBE_AGC_GAIN_Table[];

/* Gain for use with the high pass filter */
extern const LVM_INT32 LVDBE_AGC_HPFGAIN_Table[];

/************************************************************************************/
/*                                                                                  */
/*    Volume control gain and time constant tables                                  */
/*                                                                                  */
/************************************************************************************/

/* dB to linear conversion table */
extern const LVM_INT16 LVDBE_VolumeTable[];

extern const LVM_INT16 LVDBE_VolumeTCTable[];

extern const LVM_INT16 LVDBE_MixerTCTable[];

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif /* __LVBDE_TABLES_H__ */
