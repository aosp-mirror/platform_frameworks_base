/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef ANDROID_EFFECTDOWNMIX_H_
#define ANDROID_EFFECTDOWNMIX_H_

#include <audio_effects/effect_downmix.h>
#include <audio_utils/primitives.h>
#include <system/audio.h>

/*------------------------------------
 * definitions
 *------------------------------------
*/

#define DOWNMIX_OUTPUT_CHANNELS AUDIO_CHANNEL_OUT_STEREO

typedef enum {
    DOWNMIX_STATE_UNINITIALIZED,
    DOWNMIX_STATE_INITIALIZED,
    DOWNMIX_STATE_ACTIVE,
} downmix_state_t;

/* parameters for each downmixer */
typedef struct {
    downmix_state_t state;
    downmix_type_t type;
    bool apply_volume_correction;
    uint8_t input_channel_count;
} downmix_object_t;


typedef struct downmix_module_s {
    const struct effect_interface_s *itfe;
    effect_config_t config;
    downmix_object_t context;
} downmix_module_t;


/*------------------------------------
 * Effect API
 *------------------------------------
*/
int32_t DownmixLib_QueryNumberEffects(uint32_t *pNumEffects);
int32_t DownmixLib_QueryEffect(uint32_t index,
        effect_descriptor_t *pDescriptor);
int32_t DownmixLib_Create(const effect_uuid_t *uuid,
        int32_t sessionId,
        int32_t ioId,
        effect_handle_t *pHandle);
int32_t DownmixLib_Release(effect_handle_t handle);
int32_t DownmixLib_GetDescriptor(const effect_uuid_t *uuid,
        effect_descriptor_t *pDescriptor);

static int Downmix_Process(effect_handle_t self,
        audio_buffer_t *inBuffer,
        audio_buffer_t *outBuffer);
static int Downmix_Command(effect_handle_t self,
        uint32_t cmdCode,
        uint32_t cmdSize,
        void *pCmdData,
        uint32_t *replySize,
        void *pReplyData);
static int Downmix_GetDescriptor(effect_handle_t self,
        effect_descriptor_t *pDescriptor);


/*------------------------------------
 * internal functions
 *------------------------------------
*/
int Downmix_Init(downmix_module_t *pDwmModule);
int Downmix_Configure(downmix_module_t *pDwmModule, effect_config_t *pConfig, bool init);
int Downmix_Reset(downmix_object_t *pDownmixer, bool init);
int Downmix_setParameter(downmix_object_t *pDownmixer, int32_t param, size_t size, void *pValue);
int Downmix_getParameter(downmix_object_t *pDownmixer, int32_t param, size_t *pSize, void *pValue);

void Downmix_foldFromQuad(int16_t *pSrc, int16_t*pDst, size_t numFrames, bool accumulate);
void Downmix_foldFromSurround(int16_t *pSrc, int16_t*pDst, size_t numFrames, bool accumulate);
void Downmix_foldFrom5Point1(int16_t *pSrc, int16_t*pDst, size_t numFrames, bool accumulate);
void Downmix_foldFrom7Point1(int16_t *pSrc, int16_t*pDst, size_t numFrames, bool accumulate);

#endif /*ANDROID_EFFECTDOWNMIX_H_*/
