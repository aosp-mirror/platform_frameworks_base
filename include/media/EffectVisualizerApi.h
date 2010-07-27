/*
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

#ifndef ANDROID_EFFECTVISUALIZERAPI_H_
#define ANDROID_EFFECTVISUALIZERAPI_H_

#include <media/EffectApi.h>

#if __cplusplus
extern "C" {
#endif

#ifndef OPENSL_ES_H_
static const effect_uuid_t SL_IID_VISUALIZATION_ =
    { 0xe46b26a0, 0xdddd, 0x11db, 0x8afd, { 0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b } };
const effect_uuid_t * const SL_IID_VISUALIZATION = &SL_IID_VISUALIZATION_;
#endif //OPENSL_ES_H_

#define VISUALIZER_CAPTURE_SIZE_MAX 1024  // maximum capture size in samples
#define VISUALIZER_CAPTURE_SIZE_MIN 128   // minimum capture size in samples

/* enumerated parameters for Visualizer effect */
typedef enum
{
    VISU_PARAM_CAPTURE_SIZE,        // Sets the number PCM samples in the capture.
} t_visualizer_params;

/* commands */
typedef enum
{
    VISU_CMD_CAPTURE = EFFECT_CMD_FIRST_PROPRIETARY, // Gets the latest PCM capture.
}t_visualizer_cmds;

// VISU_CMD_CAPTURE retrieves the latest PCM snapshot captured by the visualizer engine.
// It returns the number of samples specified by VISU_PARAM_CAPTURE_SIZE
// in 8 bit unsigned format (0 = 0x80)

#if __cplusplus
}  // extern "C"
#endif


#endif /*ANDROID_EFFECTVISUALIZERAPI_H_*/
