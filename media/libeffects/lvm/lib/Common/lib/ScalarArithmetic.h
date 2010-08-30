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

#ifndef __SCALARARITHMETIC_H__
#define __SCALARARITHMETIC_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/*######################################################################################*/
/*  Include files                                                                       */
/*######################################################################################*/

#include "LVM_Types.h"

/*######################################################################################*/
/*  Extern function prototypes                                                          */
/*######################################################################################*/

/* Absolute value including the corner case for the extreme negative value */
LVM_INT32   Abs_32(LVM_INT32     input);

/****************************************************************************************
 *  Name        : dB_to_Lin32()
 *  Input       : Signed 16-bit integer
 *                  MSB (16) = sign bit
 *                  (15->05) = integer part
 *                  (04->01) = decimal part
 *  Output      : Signed 32-bit integer
 *                  MSB (32) = sign bit
 *                  (31->16) = integer part
 *                  (15->01) = decimal part
 *  Returns     : Lin value format 1.16.15
 ****************************************************************************************/

LVM_INT32 dB_to_Lin32(LVM_INT16  db_fix);

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif  /* __SCALARARITHMETIC_H__ */


