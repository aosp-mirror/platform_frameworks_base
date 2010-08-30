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

#ifndef __LVCS_REVERBGENERATOR_H__
#define __LVCS_REVERBGENERATOR_H__

#ifdef __cplusplus
extern "C" {
#endif /* __cplusplus */


/************************************************************************************/
/*                                                                                  */
/*    Includes                                                                      */
/*                                                                                  */
/************************************************************************************/

#include "LVC_Mixer.h"


/************************************************************************************/
/*                                                                                  */
/*    Defines                                                                       */
/*                                                                                  */
/************************************************************************************/

#define     HEADPHONEGAINPROC           LVCS_HEADPHONE_PROCGAIN
#define     HEADPHONEGAINUNPROC         LVCS_HEADPHONE_UNPROCGAIN


/************************************************************************************/
/*                                                                                  */
/*    Structures                                                                    */
/*                                                                                  */
/************************************************************************************/


/* Reverberation module structure */
typedef struct
{

    /* Stereo delay */
    LVM_INT16                   DelaySize;
    LVM_INT16                   DelayOffset;
    LVM_INT16                   ProcGain;
    LVM_INT16                   UnprocGain;
    LVM_INT16                    StereoSamples[2*LVCS_STEREODELAY_CS_48KHZ];

    /* Reverb Level */
    LVM_INT16                   ReverbLevel;

    /* Filter */
    void                        (*pBiquadCallBack) (Biquad_Instance_t*, LVM_INT16*, LVM_INT16*, LVM_INT16);

} LVCS_ReverbGenerator_t;


/************************************************************************************/
/*                                                                                    */
/*    Function prototypes                                                                */
/*                                                                                    */
/************************************************************************************/

LVCS_ReturnStatus_en LVCS_ReverbGeneratorInit(LVCS_Handle_t     hInstance,
                                                 LVCS_Params_t  *pParams);

LVCS_ReturnStatus_en LVCS_ReverbGenerator(LVCS_Handle_t         hInstance,
                                          const LVM_INT16       *pInput,
                                          LVM_INT16             *pOutput,
                                          LVM_UINT16            NumSamples);

#ifdef __cplusplus
}
#endif /* __cplusplus */

#endif  /* REVERB_H */
