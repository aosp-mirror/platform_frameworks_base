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

#ifndef __LVCS_STEREOENHANCER_H__
#define __LVCS_STEREOENHANCER_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/************************************************************************************/
/*                                                                                  */
/*    Includes                                                                      */
/*                                                                                  */
/************************************************************************************/

#include "Filters.h"                        /* Filter definitions */
#include "LVCS_Headphone_Coeffs.h"          /* Headphone coefficients */
#include "BIQUAD.h"


/************************************************************************************/
/*                                                                                  */
/*    Structures                                                                    */
/*                                                                                  */
/************************************************************************************/

/* Stereo enhancer structure */
typedef struct
{
    /*
     * Middle filter
     */
    void                    (*pBiquadCallBack_Mid)(Biquad_Instance_t*, LVM_INT16*, LVM_INT16*, LVM_INT16);

    /*
     * Side filter
     */
    void                    (*pBiquadCallBack_Side)(Biquad_Instance_t*, LVM_INT16*, LVM_INT16*, LVM_INT16);

      LVM_UINT16              MidGain;            /* Middle gain in mobile speaker mode */

} LVCS_StereoEnhancer_t;


/************************************************************************************/
/*                                                                                  */
/*    Function prototypes                                                           */
/*                                                                                  */
/************************************************************************************/

LVCS_ReturnStatus_en LVCS_SEnhancerInit(LVCS_Handle_t        hInstance,
                                        LVCS_Params_t        *pParams);

LVCS_ReturnStatus_en LVCS_StereoEnhancer(LVCS_Handle_t        hInstance,
                                         const LVM_INT16    *pInData,
                                         LVM_INT16            *pOutData,
                                         LVM_UINT16            NumSamples);


#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif  /* STEREOENHANCE_H */
