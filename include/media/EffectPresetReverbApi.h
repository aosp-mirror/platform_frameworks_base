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

#ifndef ANDROID_EFFECTPRESETREVERBAPI_H_
#define ANDROID_EFFECTPRESETREVERBAPI_H_

#include <media/EffectApi.h>

#if __cplusplus
extern "C" {
#endif

#ifndef OPENSL_ES_H_
static const effect_uuid_t SL_IID_PRESETREVERB_ = { 0x47382d60, 0xddd8, 0x11db, 0xbf3a, { 0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b } };
const effect_uuid_t * const SL_IID_PRESETREVERB = &SL_IID_PRESETREVERB_;
#endif //OPENSL_ES_H_

/* enumerated parameter settings for preset reverb effect */
typedef enum
{
    REVERB_PARAM_PRESET
} t_preset_reverb_params;


typedef enum
{
    REVERB_PRESET_NONE,
    REVERB_PRESET_SMALLROOM,
    REVERB_PRESET_MEDIUMROOM,
    REVERB_PRESET_LARGEROOM,
    REVERB_PRESET_MEDIUMHALL,
    REVERB_PRESET_LARGEHALL,
    REVERB_PRESET_PLATE,
    REVERB_PRESET_LAST = REVERB_PRESET_PLATE
} t_reverb_presets;

#if __cplusplus
}  // extern "C"
#endif


#endif /*ANDROID_EFFECTPRESETREVERBAPI_H_*/
