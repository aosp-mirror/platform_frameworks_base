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

#ifndef _COMP_LIM_H
#define _COMP_LIM_H

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/************************************************************************************/
/*                                                                                  */
/*  Includes                                                                        */
/*                                                                                  */
/************************************************************************************/

#include "LVM_Types.h"


/************************************************************************************/
/*                                                                                  */
/*  Structures                                                                      */
/*                                                                                  */
/************************************************************************************/

typedef struct /* Compressor state */
{
    /* Normaliser */
    LVM_INT16   Norm_Attack;        /* Attack time constant of the Normaliser integrator */
    LVM_INT16   Norm_Decay;         /* Decay time constant of the Normaliser integrator */
    LVM_INT32   NormInt;            /* Normaliser integrator current value */
    LVM_INT16   Shift;              /* Shift gain */
    LVM_INT16   Threshold;          /* Target threshold */

    /* Compressor */
    LVM_INT16   Comp_Atten;         /* Attenuation applied before soft knee compressor */
    LVM_INT16   Comp_Attack_S;      /* Attack time constant of the slow integrator */
    LVM_INT16   Comp_Decay_S;       /* Decay time constant of slow the integrator */
    LVM_INT16   Comp_Attack_F;      /* Attack time constant of fast the integrator */
    LVM_INT16   Comp_Decay_F;       /* Decay time constant of fast the integrator */
    LVM_INT16   SoftClipGain;       /* Soft clip gain control */
    LVM_INT32   CompIntSlow;        /* Compressor slow integrator current value */
    LVM_INT32   CompIntFast;        /* Compressor fast integrator current value */


} CompLim_Instance_t;


/************************************************************************************/
/*                                                                                  */
/*  Function Prototypes                                                             */
/*                                                                                  */
/************************************************************************************/

void NonLinComp_D16(LVM_INT16        Gain,
                    LVM_INT16        *pSterBfIn,
                    LVM_INT16        *pSterBfOut,
                    LVM_INT32        BlockLength);


#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif /* #ifndef _COMP_LIM_H */



