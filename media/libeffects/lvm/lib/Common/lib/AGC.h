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

#ifndef __AGC_H__
#define __AGC_H__


#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

/**********************************************************************************/
/*                                                                                */
/*    Includes                                                                    */
/*                                                                                */
/**********************************************************************************/

#include "LVM_Types.h"


/**********************************************************************************/
/*                                                                                */
/*    Types                                                                       */
/*                                                                                */
/**********************************************************************************/

typedef struct
{
    LVM_INT32  AGC_Gain;                        /* The current AGC gain */
    LVM_INT32  AGC_MaxGain;                     /* The maximum AGC gain */
    LVM_INT32  Volume;                          /* The current volume setting */
    LVM_INT32  Target;                          /* The target volume setting */
    LVM_INT32  AGC_Target;                      /* AGC target level */
    LVM_INT16  AGC_Attack;                      /* AGC attack scaler */
    LVM_INT16  AGC_Decay;                       /* AGC decay scaler */
    LVM_INT16  AGC_GainShift;                   /* The gain shift */
    LVM_INT16  VolumeShift;                     /* Volume shift scaling */
    LVM_INT16  VolumeTC;                        /* Volume update time constant */

} AGC_MIX_VOL_2St1Mon_D32_t;


/**********************************************************************************/
/*                                                                                */
/*    Function Prototypes                                                              */
/*                                                                                */
/**********************************************************************************/

void AGC_MIX_VOL_2St1Mon_D32_WRA(AGC_MIX_VOL_2St1Mon_D32_t  *pInstance,     /* Instance pointer */
                                 const LVM_INT32            *pStSrc,        /* Stereo source */
                                 const LVM_INT32            *pMonoSrc,      /* Mono source */
                                 LVM_INT32                  *pDst,          /* Stereo destination */
                                 LVM_UINT16                 n);             /* Number of samples */

#ifdef __cplusplus
}
#endif /* __cplusplus */


#endif  /* __AGC_H__ */










