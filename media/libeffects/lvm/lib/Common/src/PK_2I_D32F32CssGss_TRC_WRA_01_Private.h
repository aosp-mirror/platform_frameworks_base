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

#ifndef _PK_2I_D32F32CSSGSS_TRC_WRA_01_PRIVATE_H_
#define _PK_2I_D32F32CSSGSS_TRC_WRA_01_PRIVATE_H_


/* The internal state variables are implemented in a (for the user)  hidden structure */
/* In this (private) file, the internal structure is declared fro private use.        */
typedef struct _Filter_State_
{
  LVM_INT32 *       pDelays;        /* pointer to the delayed samples (data of 32 bits)   */
  LVM_INT32         coefs[5];       /* pointer to the filter coefficients */
}Filter_State;

typedef Filter_State * PFilter_State ;

#endif /* _PK_2I_D32F32CSSGSS_TRC_WRA_01_PRIVATE_H_ */
