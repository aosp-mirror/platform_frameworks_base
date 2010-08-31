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

#ifndef FILTERS_H
#define FILTERS_H

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

#include "LVM_Types.h"

/************************************************************************************/
/*                                                                                  */
/*    Structures                                                                    */
/*                                                                                  */
/************************************************************************************/

/*
 * Biquad with coefficients A0, A1, A2, B1 and B2 coefficients
 */
/* Single precision (16-bit) Biquad section coefficients */
typedef struct
{
        LVM_INT16   A0;
        LVM_INT16   A1;
        LVM_INT16   A2;
        LVM_INT16   B1;
        LVM_INT16   B2;
        LVM_UINT16  Scale;
} BiquadA012B12CoefsSP_t;


/*
 * Biquad with coefficients A0, A1 and B1 coefficients
 */
/* Single precision (16-bit) Biquad section coefficients */
typedef struct
{
        LVM_INT16   A0;
        LVM_INT16   A1;
        LVM_INT16   B1;
        LVM_UINT16  Scale;
} BiquadA01B1CoefsSP_t;


#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif      /* FILTERS_H */

