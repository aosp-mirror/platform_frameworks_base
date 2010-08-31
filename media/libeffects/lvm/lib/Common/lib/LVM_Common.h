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

/****************************************************************************************/
/*                                                                                      */
/*  Header file for the common definitions used within the bundle and its algorithms.   */
/*                                                                                      */
/*  This files includes all definitions, types, structures and function prototypes.     */
/*                                                                                      */
/****************************************************************************************/


#ifndef __LVM_COMMON_H__
#define __LVM_COMMON_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/****************************************************************************************/
/*                                                                                      */
/*  Includes                                                                            */
/*                                                                                      */
/****************************************************************************************/
#include "LVM_Types.h"


/****************************************************************************************/
/*                                                                                      */
/*  Definitions                                                                         */
/*                                                                                      */
/****************************************************************************************/
/* Algorithm identification */
#define ALGORITHM_NONE_ID      0x0000
#define ALGORITHM_CS_ID        0x0100
#define ALGORITHM_EQNB_ID      0x0200
#define ALGORITHM_DBE_ID       0x0300
#define ALGORITHM_VC_ID        0x0500
#define ALGORITHM_TE_ID        0x0600

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif      /* __LVM_COMMON_H__ */

