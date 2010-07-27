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

#ifndef ANDROID_EFFECTEQUALIZERAPI_H_
#define ANDROID_EFFECTEQUALIZERAPI_H_

#include <media/EffectApi.h>

#ifndef OPENSL_ES_H_
static const effect_uuid_t SL_IID_EQUALIZER_ = { 0x0bed4300, 0xddd6, 0x11db, 0x8f34, { 0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b } };
const effect_uuid_t * const SL_IID_EQUALIZER = &SL_IID_EQUALIZER_;
#endif //OPENSL_ES_H_

#if __cplusplus
extern "C" {
#endif

/* enumerated parameters for Equalizer effect */
typedef enum
{
    EQ_PARAM_NUM_BANDS,             // Gets the number of frequency bands that the equalizer supports.
    EQ_PARAM_LEVEL_RANGE,           // Returns the minimum and maximum band levels supported.
    EQ_PARAM_BAND_LEVEL,            // Gets/Sets the gain set for the given equalizer band.
    EQ_PARAM_CENTER_FREQ,           // Gets the center frequency of the given band.
    EQ_PARAM_BAND_FREQ_RANGE,       // Gets the frequency range of the given frequency band.
    EQ_PARAM_GET_BAND,              // Gets the band that has the most effect on the given frequency.
    EQ_PARAM_CUR_PRESET,            // Gets/Sets the current preset.
    EQ_PARAM_GET_NUM_OF_PRESETS,    // Gets the total number of presets the equalizer supports.
    EQ_PARAM_GET_PRESET_NAME,       // Gets the preset name based on the index.
    EQ_PARAM_PROPERTIES             // Gets/Sets all parameters at a time.
} t_equalizer_params;

//t_equalizer_settings groups all current equalizer setting for backup and restore.
typedef struct s_equalizer_settings {
    uint16_t curPreset;
    uint16_t numBands;
    uint16_t bandLevels[];
} t_equalizer_settings;

#if __cplusplus
}  // extern "C"
#endif


#endif /*ANDROID_EFFECTEQUALIZERAPI_H_*/
