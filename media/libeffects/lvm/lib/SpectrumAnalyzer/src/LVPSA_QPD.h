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

#ifndef _LVPSA_QPD_H_
#define _LVPSA_QPD_H_

#include "LVM_Types.h"


#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */

typedef struct
{
  LVM_INT32                            *pDelay;        /* pointer to the delayed samples (data of 32 bits)   */
  LVM_INT32                            Coefs[2];       /* pointer to the filter coefficients */
}QPD_State_t, *pQPD_State_t;

typedef struct
{
    LVM_INT32 KP;    /*should store a0*/
    LVM_INT32 KM;    /*should store b2*/

} QPD_C32_Coefs, *PQPD_C32_Coefs;

typedef struct
{
    LVM_INT32 Storage[1];

} QPD_Taps_t, *pQPD_Taps_t;

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_QPD_Process                                           */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Apply downsampling, post gain, quasi peak filtering and write the levels values */
/*  in the buffer every 20 ms.                                                      */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*                                                                                  */
/* RETURNS:             void                                                        */
/*                                                                                  */
/************************************************************************************/
void LVPSA_QPD_Process (            void                               *hInstance,
                                    LVM_INT16                          *pInSamps,
                                    LVM_INT16                           numSamples,
                                    LVM_INT16                           BandIndex);

/************************************************************************************/
/*                                                                                  */
/* FUNCTION:            LVPSA_QPD_Init                                              */
/*                                                                                  */
/* DESCRIPTION:                                                                     */
/*  Initialize a quasi peak filter instance.                                        */
/*                                                                                  */
/* PARAMETERS:                                                                      */
/*  pInstance           Pointer to the instance                                     */
/*   pTaps               Pointer to the filter's taps                               */
/*   pCoef               Pointer to the filter's coefficients                       */
/*                                                                                  */
/* RETURNS:     void                                                                */
/*                                                                                  */
/************************************************************************************/
void LVPSA_QPD_Init (   QPD_State_t       *pInstance,
                        QPD_Taps_t        *pTaps,
                        QPD_C32_Coefs     *pCoef     );


#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif

