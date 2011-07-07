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

#ifndef ANDROID_EFFECTREVERB_H_
#define ANDROID_EFFECTREVERB_H_

#include <audio_effects/effect_environmentalreverb.h>
#include <audio_effects/effect_presetreverb.h>

#if __cplusplus
extern "C" {
#endif

#define MAX_NUM_BANDS           5
#define MAX_CALL_SIZE           256
#define LVREV_MAX_T60           7000
#define LVREV_MAX_REVERB_LEVEL  2000
#define LVREV_MAX_FRAME_SIZE    2560
#define LVREV_CUP_LOAD_ARM9E    470    // Expressed in 0.1 MIPS
#define LVREV_MEM_USAGE         71+(LVREV_MAX_FRAME_SIZE>>7)     // Expressed in kB
//#define LVM_PCM

typedef struct _LPFPair_t
{
    int16_t Room_HF;
    int16_t LPF;
} LPFPair_t;
#if __cplusplus
}  // extern "C"
#endif


#endif /*ANDROID_EFFECTREVERB_H_*/
