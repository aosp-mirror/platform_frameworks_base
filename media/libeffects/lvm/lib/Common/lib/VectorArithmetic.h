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

#ifndef _VECTOR_ARITHMETIC_H_
#define _VECTOR_ARITHMETIC_H_


#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

#include "LVM_Types.h"

/**********************************************************************************
    VARIOUS FUNCTIONS
***********************************************************************************/

void LoadConst_16(            const LVM_INT16 val,
                                    LVM_INT16 *dst,
                                    LVM_INT16 n );

void LoadConst_32(            const LVM_INT32 val,
                                    LVM_INT32 *dst,
                                    LVM_INT16 n );

void Copy_16(                 const LVM_INT16 *src,
                                    LVM_INT16 *dst,
                                    LVM_INT16 n );

/*********************************************************************************
 * note: In Mult3s_16x16() saturation of result is not taken care when           *
 *       overflow occurs.                                                        *
 *       For example when *src = 0x8000, val = *0x8000                           *
 *       The function gives the output as 0x8000 instead of 0x7fff               *
 *       This is the only case which will give wrong result.                     *
 *       For more information refer to Vector_Arithmetic.doc in /doc folder      *
 *********************************************************************************/
void Mult3s_16x16(            const LVM_INT16 *src,
                              const LVM_INT16 val,
                                    LVM_INT16 *dst,
                                    LVM_INT16 n);

/*********************************************************************************
 * note: In Mult3s_32x16() saturation of result is not taken care when           *
 *       overflow occurs.                                                        *
 *       For example when *src = 0x8000000, val = *0x8000                        *
 *       The function gives the output as 0x8000000 instead of 0x7fffffff        *
 *       This is the only extreme condition which is giving unexpected result    *
 *       For more information refer to Vector_Arithmetic.doc in /doc folder      *
 *********************************************************************************/
void Mult3s_32x16(            const LVM_INT32  *src,
                              const LVM_INT16 val,
                                    LVM_INT32  *dst,
                                    LVM_INT16 n);

void DelayMix_16x16(          const LVM_INT16 *src,
                                    LVM_INT16 *delay,
                                    LVM_INT16 size,
                                    LVM_INT16 *dst,
                                    LVM_INT16 *pOffset,
                                    LVM_INT16 n);

void DelayWrite_32(           const LVM_INT32  *src,               /* Source 1, to be delayed */
                                    LVM_INT32  *delay,             /* Delay buffer */
                                    LVM_UINT16 size,               /* Delay size */
                                    LVM_UINT16 *pOffset,           /* Delay offset */
                                    LVM_INT16 n);

void Add2_Sat_16x16(          const LVM_INT16 *src,
                                    LVM_INT16 *dst,
                                    LVM_INT16 n );

void Add2_Sat_32x32(          const LVM_INT32  *src,
                                    LVM_INT32  *dst,
                                    LVM_INT16 n );

void Mac3s_Sat_16x16(         const LVM_INT16 *src,
                              const LVM_INT16 val,
                                    LVM_INT16 *dst,
                                    LVM_INT16 n);

void Mac3s_Sat_32x16(         const LVM_INT32  *src,
                              const LVM_INT16 val,
                                    LVM_INT32  *dst,
                                    LVM_INT16 n);

void DelayAllPass_Sat_32x16To32(    LVM_INT32  *delay,              /* Delay buffer */
                                    LVM_UINT16 size,                /* Delay size */
                                    LVM_INT16 coeff,                /* All pass filter coefficient */
                                    LVM_UINT16 DelayOffset,         /* Simple delay offset */
                                    LVM_UINT16 *pAllPassOffset,     /* All pass filter delay offset */
                                    LVM_INT32  *dst,                /* Source/destination */
                                    LVM_INT16 n);

/**********************************************************************************
    SHIFT FUNCTIONS
***********************************************************************************/

void Shift_Sat_v16xv16 (      const LVM_INT16 val,
                              const LVM_INT16 *src,
                                    LVM_INT16 *dst,
                                    LVM_INT16 n);

void Shift_Sat_v32xv32 (      const LVM_INT16 val,
                              const LVM_INT32 *src,
                                    LVM_INT32 *dst,
                                    LVM_INT16 n);

/**********************************************************************************
    AUDIO FORMAT CONVERSION FUNCTIONS
***********************************************************************************/

void MonoTo2I_16(             const LVM_INT16 *src,
                                    LVM_INT16 *dst,
                                    LVM_INT16 n);

void MonoTo2I_32(             const LVM_INT32  *src,
                                    LVM_INT32  *dst,
                                    LVM_INT16 n);

void From2iToMono_32(         const LVM_INT32  *src,
                                    LVM_INT32  *dst,
                                    LVM_INT16 n);

void MSTo2i_Sat_16x16(        const LVM_INT16 *srcM,
                              const LVM_INT16 *srcS,
                                    LVM_INT16 *dst,
                                    LVM_INT16 n );

void From2iToMS_16x16(        const LVM_INT16 *src,
                                    LVM_INT16 *dstM,
                                    LVM_INT16 *dstS,
                                    LVM_INT16 n );

void From2iToMono_16(         const LVM_INT16 *src,
                                    LVM_INT16 *dst,
                                    LVM_INT16 n);

void JoinTo2i_32x32(          const LVM_INT32  *srcL,
                              const LVM_INT32  *srcR,
                                    LVM_INT32  *dst,
                                    LVM_INT16 n );

/**********************************************************************************
    DATA TYPE CONVERSION FUNCTIONS
***********************************************************************************/

void Int16LShiftToInt32_16x32(const LVM_INT16 *src,
                                    LVM_INT32  *dst,
                                    LVM_INT16 n,
                                    LVM_INT16 shift );

void Int32RShiftToInt16_Sat_32x16(const  LVM_INT32  *src,
                                    LVM_INT16 *dst,
                                    LVM_INT16 n,
                                    LVM_INT16 shift );

#ifdef __cplusplus
}
#endif /* __cplusplus */


/**********************************************************************************/

#endif  /* _VECTOR_ARITHMETIC_H_ */

/**********************************************************************************/
