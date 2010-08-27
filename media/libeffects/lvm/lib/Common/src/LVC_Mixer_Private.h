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

#ifndef __LVC_MIXER_PRIVATE_H__
#define __LVC_MIXER_PRIVATE_H__

/**********************************************************************************
   INCLUDE FILES
***********************************************************************************/

#include "LVC_Mixer.h"
#include "VectorArithmetic.h"

/* Instance parameter structure */
typedef struct
{
    /* General */
    LVM_INT32                       Target;                 /* 32 bit number specifying fractional value of Target Gain */
    LVM_INT32                       Current;                /* 32 bit number specifying fractional valude of Current Gain */
    LVM_INT32                       Shift;                  /* Left Shift for Integer part of Gain */
    LVM_INT32                       Delta;                  /* 32 bit number specifying the fractional value of Delta Gain */
} Mix_Private_st;



/**********************************************************************************
   DEFINITIONS
***********************************************************************************/
#define LVCore_MixInSoft_D32C31_SAT    LVCore_InSoft_D32C31_SAT
#define LVCore_MixSoft_1St_D32C31_WRA  LVCore_Soft_1St_D32C31_WRA
#define LVCore_MixHard_2St_D32C31_SAT  LVCore_Hard_2St_D32C31_SAT

/**********************************************************************************
   FUNCTION PROTOTYPES (LOW LEVEL SUBFUNCTIONS)
***********************************************************************************/

/*** 16 bit functions *************************************************************/

void LVC_Core_MixInSoft_D16C31_SAT( LVMixer3_st *pInstance,
                                    const LVM_INT16     *src,
                                          LVM_INT16     *dst,
                                          LVM_INT16     n);

void LVC_Core_MixSoft_1St_D16C31_WRA( LVMixer3_st *pInstance,
                                    const LVM_INT16     *src,
                                          LVM_INT16     *dst,
                                          LVM_INT16     n);

void LVC_Core_MixHard_2St_D16C31_SAT( LVMixer3_st *pInstance1,
                                    LVMixer3_st         *pInstance2,
                                    const LVM_INT16     *src1,
                                    const LVM_INT16     *src2,
                                          LVM_INT16     *dst,
                                          LVM_INT16     n);

/**********************************************************************************/
/* For applying different gains to Left and right chennals                        */
/* ptrInstance1 applies to Left channel                                           */
/* ptrInstance2 applies to Right channel                                          */
/* Gain values should not be more that 1.0                                        */
/**********************************************************************************/

void LVC_Core_MixSoft_1St_2i_D16C31_WRA( LVMixer3_st        *ptrInstance1,
                                         LVMixer3_st        *ptrInstance2,
                                         const LVM_INT16    *src,
                                         LVM_INT16          *dst,   /* dst can be equal to src */
                                         LVM_INT16          n);     /* Number of stereo samples */

/**********************************************************************************/
/* For applying different gains to Left and right chennals                        */
/* ptrInstance1 applies to Left channel                                           */
/* ptrInstance2 applies to Right channel                                          */
/* Gain values should not be more that 1.0                                        */
/**********************************************************************************/
void LVC_Core_MixHard_1St_2i_D16C31_SAT( LVMixer3_st        *ptrInstance1,
                                         LVMixer3_st        *ptrInstance2,
                                         const LVM_INT16    *src,
                                         LVM_INT16          *dst,    /* dst can be equal to src */
                                         LVM_INT16          n);      /* Number of stereo samples */



/*** 32 bit functions *************************************************************/

void LVC_Core_MixInSoft_D32C31_SAT( LVMixer3_st *pInstance,
                                    const LVM_INT32     *src,
                                          LVM_INT32     *dst,
                                          LVM_INT16     n);

void LVC_Core_MixSoft_1St_D32C31_WRA( LVMixer3_st *pInstance,
                                    const LVM_INT32     *src,
                                          LVM_INT32     *dst,
                                          LVM_INT16     n);

void LVC_Core_MixHard_2St_D32C31_SAT( LVMixer3_st *pInstance1,
                                    LVMixer3_st         *pInstance2,
                                    const LVM_INT32     *src1,
                                    const LVM_INT32     *src2,
                                          LVM_INT32     *dst,
                                          LVM_INT16     n);

/**********************************************************************************/

#endif //#ifndef __LVC_MIXER_PRIVATE_H__










