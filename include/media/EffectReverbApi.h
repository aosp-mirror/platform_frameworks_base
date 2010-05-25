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

#ifndef ANDROID_EFFECTREVERBAPI_H_
#define ANDROID_EFFECTREVERBAPI_H_

#include <media/EffectApi.h>

#if __cplusplus
extern "C" {
#endif

// TODO: include OpenSLES_IID.h instead
static const effect_uuid_t SL_IID_ENVIRONMENTALREVERB_ = { 0xc2e5d5f0, 0x94bd, 0x4763, 0x9cac, { 0x4e, 0x23, 0x4d, 0x6, 0x83, 0x9e } };
const effect_uuid_t * const SL_IID_ENVIRONMENTALREVERB = &SL_IID_ENVIRONMENTALREVERB_;

static const effect_uuid_t SL_IID_PRESETREVERB_ = { 0x47382d60, 0xddd8, 0x11db, 0xbf3a, { 0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b } };
const effect_uuid_t * const SL_IID_PRESETREVERB = &SL_IID_PRESETREVERB_;

/* enumerated parameter settings for Reverb effect */
typedef enum
{
    REVERB_PARAM_BYPASS,
    REVERB_PARAM_PRESET,
    // Parameters below are as defined in OpenSL ES specification for environmental reverb interface
    REVERB_PARAM_ROOM_LEVEL,            // in millibels,    range -6000 to 0
    REVERB_PARAM_ROOM_HF_LEVEL,         // in millibels,    range -4000 to 0
    REVERB_PARAM_DECAY_TIME,            // in milliseconds, range 100 to 20000
    REVERB_PARAM_DECAY_HF_RATIO,        // in permilles,    range 100 to 1000
    REVERB_PARAM_REFLECTIONS_LEVEL,     // in millibels,    range -6000 to 0
    REVERB_PARAM_REFLECTIONS_DELAY,     // in milliseconds, range 0 to 65
    REVERB_PARAM_REVERB_LEVEL,          // in millibels,    range -6000 to 0
    REVERB_PARAM_REVERB_DELAY,          // in milliseconds, range 0 to 65
    REVERB_PARAM_DIFFUSION,             // in permilles,    range 0 to 1000
    REVERB_PARAM_DENSITY,               // in permilles,    range 0 to 1000
    REVERB_PARAM_PROPERTIES
} t_reverb_params;


typedef enum
{
    REVERB_PRESET_LARGE_HALL,
    REVERB_PRESET_HALL,
    REVERB_PRESET_CHAMBER,
    REVERB_PRESET_ROOM,
} t_reverb_presets;

//t_reverb_properties is equal to SLEnvironmentalReverbSettings defined in OpenSL ES specification.
typedef struct s_reverb_properties {
    int16_t roomLevel;
    int16_t roomHFLevel;
    int32_t decayTime;
    int16_t decayHFRatio;
    int16_t reflectionsLevel;
    int32_t reflectionsDelay;
    int32_t reverbDelay;
    int16_t reverbLevel;
    int16_t diffusion;
    int16_t density;
    int16_t padding;
} t_reverb_properties;


#if __cplusplus
}  // extern "C"
#endif


#endif /*ANDROID_EFFECTREVERBAPI_H_*/
