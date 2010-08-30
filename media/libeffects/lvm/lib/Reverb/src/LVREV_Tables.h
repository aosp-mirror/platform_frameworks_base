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


#ifndef _LVREV_TABLES_H_
#define _LVREV_TABLES_H_

#ifdef __cplusplus
extern "C" {
#endif


/****************************************************************************************/
/*                                                                                      */
/*  Includes                                                                            */
/*                                                                                      */
/****************************************************************************************/
#include "LVREV_Private.h"

/****************************************************************************************/
/*                                                                                      */
/*  Definitions                                                                         */
/*                                                                                      */
/****************************************************************************************/

extern const    LVM_UINT16  LVM_FsTable[];
extern          LVM_UINT16  LVM_GetFsFromTable(LVM_Fs_en FsIndex);
extern          LVM_INT32   LVREV_GainPolyTable[24][5];

#ifdef __cplusplus
}
#endif

#endif  /** _LVREV_TABLES_H_ **/

/* End of file */
