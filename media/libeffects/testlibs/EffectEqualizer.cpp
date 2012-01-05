/*
 * Copyright (C) 2009 The Android Open Source Project
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

#define LOG_TAG "Equalizer"
#define ARRAY_SIZE(array) (sizeof array / sizeof array[0])
//
#define LOG_NDEBUG 0
#include <cutils/log.h>
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <new>
#include "AudioEqualizer.h"
#include "AudioBiquadFilter.h"
#include "AudioFormatAdapter.h"
#include <audio_effects/effect_equalizer.h>


// effect_handle_t interface implementation for equalizer effect
extern "C" const struct effect_interface_s gEqualizerInterface;

enum equalizer_state_e {
    EQUALIZER_STATE_UNINITIALIZED,
    EQUALIZER_STATE_INITIALIZED,
    EQUALIZER_STATE_ACTIVE,
};

namespace android {
namespace {

// Google Graphic Equalizer UUID: e25aa840-543b-11df-98a5-0002a5d5c51b
const effect_descriptor_t gEqualizerDescriptor = {
        {0x0bed4300, 0xddd6, 0x11db, 0x8f34, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}}, // type
        {0xe25aa840, 0x543b, 0x11df, 0x98a5, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}}, // uuid
        EFFECT_CONTROL_API_VERSION,
        (EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_LAST),
        0, // TODO
        1,
        "Graphic Equalizer",
        "The Android Open Source Project",
};

/////////////////// BEGIN EQ PRESETS ///////////////////////////////////////////
const int kNumBands = 5;
const uint32_t gFreqs[kNumBands] =      { 50000, 125000, 900000, 3200000, 6300000 };
const uint32_t gBandwidths[kNumBands] = { 0,     3600,   3600,   2400,    0       };

const AudioEqualizer::BandConfig gBandsClassic[kNumBands] = {
    { 300,  gFreqs[0], gBandwidths[0] },
    { 400,  gFreqs[1], gBandwidths[1] },
    { 0,    gFreqs[2], gBandwidths[2] },
    { 200,  gFreqs[3], gBandwidths[3] },
    { -300, gFreqs[4], gBandwidths[4] }
};

const AudioEqualizer::BandConfig gBandsJazz[kNumBands] = {
    { -600, gFreqs[0], gBandwidths[0] },
    { 200,  gFreqs[1], gBandwidths[1] },
    { 400,  gFreqs[2], gBandwidths[2] },
    { -400, gFreqs[3], gBandwidths[3] },
    { -600, gFreqs[4], gBandwidths[4] }
};

const AudioEqualizer::BandConfig gBandsPop[kNumBands] = {
    { 400,  gFreqs[0], gBandwidths[0] },
    { -400, gFreqs[1], gBandwidths[1] },
    { 300,  gFreqs[2], gBandwidths[2] },
    { -400, gFreqs[3], gBandwidths[3] },
    { 600,  gFreqs[4], gBandwidths[4] }
};

const AudioEqualizer::BandConfig gBandsRock[kNumBands] = {
    { 700,  gFreqs[0], gBandwidths[0] },
    { 400,  gFreqs[1], gBandwidths[1] },
    { -400, gFreqs[2], gBandwidths[2] },
    { 400,  gFreqs[3], gBandwidths[3] },
    { 200,  gFreqs[4], gBandwidths[4] }
};

const AudioEqualizer::PresetConfig gEqualizerPresets[] = {
    { "Classic", gBandsClassic },
    { "Jazz",    gBandsJazz    },
    { "Pop",     gBandsPop     },
    { "Rock",    gBandsRock    }
};

/////////////////// END EQ PRESETS /////////////////////////////////////////////

static const size_t kBufferSize = 32;

typedef AudioFormatAdapter<AudioEqualizer, kBufferSize> FormatAdapter;

struct EqualizerContext {
    const struct effect_interface_s *itfe;
    effect_config_t config;
    FormatAdapter adapter;
    AudioEqualizer * pEqualizer;
    uint32_t state;
};

//--- local function prototypes

int Equalizer_init(EqualizerContext *pContext);
int Equalizer_setConfig(EqualizerContext *pContext, effect_config_t *pConfig);
int Equalizer_getParameter(AudioEqualizer * pEqualizer, int32_t *pParam, size_t *pValueSize, void *pValue);
int Equalizer_setParameter(AudioEqualizer * pEqualizer, int32_t *pParam, void *pValue);


//
//--- Effect Library Interface Implementation
//

extern "C" int EffectQueryNumberEffects(uint32_t *pNumEffects) {
    *pNumEffects = 1;
    return 0;
} /* end EffectQueryNumberEffects */

extern "C" int EffectQueryEffect(uint32_t index,
                                 effect_descriptor_t *pDescriptor) {
    if (pDescriptor == NULL) {
        return -EINVAL;
    }
    if (index > 0) {
        return -EINVAL;
    }
    memcpy(pDescriptor, &gEqualizerDescriptor, sizeof(effect_descriptor_t));
    return 0;
} /* end EffectQueryNext */

extern "C" int EffectCreate(effect_uuid_t *uuid,
                            int32_t sessionId,
                            int32_t ioId,
                            effect_handle_t *pHandle) {
    int ret;
    int i;

    ALOGV("EffectLibCreateEffect start");

    if (pHandle == NULL || uuid == NULL) {
        return -EINVAL;
    }

    if (memcmp(uuid, &gEqualizerDescriptor.uuid, sizeof(effect_uuid_t)) != 0) {
        return -EINVAL;
    }

    EqualizerContext *pContext = new EqualizerContext;

    pContext->itfe = &gEqualizerInterface;
    pContext->pEqualizer = NULL;
    pContext->state = EQUALIZER_STATE_UNINITIALIZED;

    ret = Equalizer_init(pContext);
    if (ret < 0) {
        ALOGW("EffectLibCreateEffect() init failed");
        delete pContext;
        return ret;
    }

    *pHandle = (effect_handle_t)pContext;
    pContext->state = EQUALIZER_STATE_INITIALIZED;

    ALOGV("EffectLibCreateEffect %p, size %d",
         pContext, AudioEqualizer::GetInstanceSize(kNumBands)+sizeof(EqualizerContext));

    return 0;

} /* end EffectCreate */

extern "C" int EffectRelease(effect_handle_t handle) {
    EqualizerContext * pContext = (EqualizerContext *)handle;

    ALOGV("EffectLibReleaseEffect %p", handle);
    if (pContext == NULL) {
        return -EINVAL;
    }

    pContext->state = EQUALIZER_STATE_UNINITIALIZED;
    pContext->pEqualizer->free();
    delete pContext;

    return 0;
} /* end EffectRelease */

extern "C" int EffectGetDescriptor(effect_uuid_t       *uuid,
                                   effect_descriptor_t *pDescriptor) {

    if (pDescriptor == NULL || uuid == NULL){
        ALOGV("EffectGetDescriptor() called with NULL pointer");
        return -EINVAL;
    }

    if (memcmp(uuid, &gEqualizerDescriptor.uuid, sizeof(effect_uuid_t)) == 0) {
        memcpy(pDescriptor, &gEqualizerDescriptor, sizeof(effect_descriptor_t));
        return 0;
    }

    return  -EINVAL;
} /* end EffectGetDescriptor */


//
//--- local functions
//

#define CHECK_ARG(cond) {                     \
    if (!(cond)) {                            \
        ALOGV("Invalid argument: "#cond);      \
        return -EINVAL;                       \
    }                                         \
}

//----------------------------------------------------------------------------
// Equalizer_setConfig()
//----------------------------------------------------------------------------
// Purpose: Set input and output audio configuration.
//
// Inputs:
//  pContext:   effect engine context
//  pConfig:    pointer to effect_config_t structure holding input and output
//      configuration parameters
//
// Outputs:
//
//----------------------------------------------------------------------------

int Equalizer_setConfig(EqualizerContext *pContext, effect_config_t *pConfig)
{
    ALOGV("Equalizer_setConfig start");

    CHECK_ARG(pContext != NULL);
    CHECK_ARG(pConfig != NULL);

    CHECK_ARG(pConfig->inputCfg.samplingRate == pConfig->outputCfg.samplingRate);
    CHECK_ARG(pConfig->inputCfg.channels == pConfig->outputCfg.channels);
    CHECK_ARG(pConfig->inputCfg.format == pConfig->outputCfg.format);
    CHECK_ARG((pConfig->inputCfg.channels == AUDIO_CHANNEL_OUT_MONO) ||
              (pConfig->inputCfg.channels == AUDIO_CHANNEL_OUT_STEREO));
    CHECK_ARG(pConfig->outputCfg.accessMode == EFFECT_BUFFER_ACCESS_WRITE
              || pConfig->outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE);
    CHECK_ARG(pConfig->inputCfg.format == AUDIO_FORMAT_PCM_8_24_BIT
              || pConfig->inputCfg.format == AUDIO_FORMAT_PCM_16_BIT);

    int channelCount;
    if (pConfig->inputCfg.channels == AUDIO_CHANNEL_OUT_MONO) {
        channelCount = 1;
    } else {
        channelCount = 2;
    }
    CHECK_ARG(channelCount <= AudioBiquadFilter::MAX_CHANNELS);

    memcpy(&pContext->config, pConfig, sizeof(effect_config_t));

    pContext->pEqualizer->configure(channelCount,
                          pConfig->inputCfg.samplingRate);

    pContext->adapter.configure(*pContext->pEqualizer, channelCount,
                        pConfig->inputCfg.format,
                        pConfig->outputCfg.accessMode);

    return 0;
}   // end Equalizer_setConfig

//----------------------------------------------------------------------------
// Equalizer_getConfig()
//----------------------------------------------------------------------------
// Purpose: Get input and output audio configuration.
//
// Inputs:
//  pContext:   effect engine context
//  pConfig:    pointer to effect_config_t structure holding input and output
//      configuration parameters
//
// Outputs:
//
//----------------------------------------------------------------------------

void Equalizer_getConfig(EqualizerContext *pContext, effect_config_t *pConfig)
{
    memcpy(pConfig, &pContext->config, sizeof(effect_config_t));
}   // end Equalizer_getConfig


//----------------------------------------------------------------------------
// Equalizer_init()
//----------------------------------------------------------------------------
// Purpose: Initialize engine with default configuration and creates
//     AudioEqualizer instance.
//
// Inputs:
//  pContext:   effect engine context
//
// Outputs:
//
//----------------------------------------------------------------------------

int Equalizer_init(EqualizerContext *pContext)
{
    int status;

    ALOGV("Equalizer_init start");

    CHECK_ARG(pContext != NULL);

    if (pContext->pEqualizer != NULL) {
        pContext->pEqualizer->free();
    }

    pContext->config.inputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
    pContext->config.inputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    pContext->config.inputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
    pContext->config.inputCfg.samplingRate = 44100;
    pContext->config.inputCfg.bufferProvider.getBuffer = NULL;
    pContext->config.inputCfg.bufferProvider.releaseBuffer = NULL;
    pContext->config.inputCfg.bufferProvider.cookie = NULL;
    pContext->config.inputCfg.mask = EFFECT_CONFIG_ALL;
    pContext->config.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_ACCUMULATE;
    pContext->config.outputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    pContext->config.outputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
    pContext->config.outputCfg.samplingRate = 44100;
    pContext->config.outputCfg.bufferProvider.getBuffer = NULL;
    pContext->config.outputCfg.bufferProvider.releaseBuffer = NULL;
    pContext->config.outputCfg.bufferProvider.cookie = NULL;
    pContext->config.outputCfg.mask = EFFECT_CONFIG_ALL;

    pContext->pEqualizer = AudioEqualizer::CreateInstance(
        NULL,
        kNumBands,
        AudioBiquadFilter::MAX_CHANNELS,
        44100,
        gEqualizerPresets,
        ARRAY_SIZE(gEqualizerPresets));

    for (int i = 0; i < kNumBands; ++i) {
        pContext->pEqualizer->setFrequency(i, gFreqs[i]);
        pContext->pEqualizer->setBandwidth(i, gBandwidths[i]);
    }

    pContext->pEqualizer->enable(true);

    Equalizer_setConfig(pContext, &pContext->config);

    return 0;
}   // end Equalizer_init


//----------------------------------------------------------------------------
// Equalizer_getParameter()
//----------------------------------------------------------------------------
// Purpose:
// Get a Equalizer parameter
//
// Inputs:
//  pEqualizer       - handle to instance data
//  pParam           - pointer to parameter
//  pValue           - pointer to variable to hold retrieved value
//  pValueSize       - pointer to value size: maximum size as input
//
// Outputs:
//  *pValue updated with parameter value
//  *pValueSize updated with actual value size
//
//
// Side Effects:
//
//----------------------------------------------------------------------------

int Equalizer_getParameter(AudioEqualizer * pEqualizer, int32_t *pParam, size_t *pValueSize, void *pValue)
{
    int status = 0;
    int32_t param = *pParam++;
    int32_t param2;
    char *name;

    switch (param) {
    case EQ_PARAM_NUM_BANDS:
    case EQ_PARAM_CUR_PRESET:
    case EQ_PARAM_GET_NUM_OF_PRESETS:
    case EQ_PARAM_BAND_LEVEL:
    case EQ_PARAM_GET_BAND:
        if (*pValueSize < sizeof(int16_t)) {
            return -EINVAL;
        }
        *pValueSize = sizeof(int16_t);
        break;

    case EQ_PARAM_LEVEL_RANGE:
        if (*pValueSize < 2 * sizeof(int16_t)) {
            return -EINVAL;
        }
        *pValueSize = 2 * sizeof(int16_t);
        break;

    case EQ_PARAM_BAND_FREQ_RANGE:
        if (*pValueSize < 2 * sizeof(int32_t)) {
            return -EINVAL;
        }
        *pValueSize = 2 * sizeof(int32_t);
        break;

    case EQ_PARAM_CENTER_FREQ:
        if (*pValueSize < sizeof(int32_t)) {
            return -EINVAL;
        }
        *pValueSize = sizeof(int32_t);
        break;

    case EQ_PARAM_GET_PRESET_NAME:
        break;

    case EQ_PARAM_PROPERTIES:
        if (*pValueSize < (2 + kNumBands) * sizeof(uint16_t)) {
            return -EINVAL;
        }
        *pValueSize = (2 + kNumBands) * sizeof(uint16_t);
        break;

    default:
        return -EINVAL;
    }

    switch (param) {
    case EQ_PARAM_NUM_BANDS:
        *(uint16_t *)pValue = (uint16_t)kNumBands;
        ALOGV("Equalizer_getParameter() EQ_PARAM_NUM_BANDS %d", *(int16_t *)pValue);
        break;

    case EQ_PARAM_LEVEL_RANGE:
        *(int16_t *)pValue = -9600;
        *((int16_t *)pValue + 1) = 4800;
        ALOGV("Equalizer_getParameter() EQ_PARAM_LEVEL_RANGE min %d, max %d",
             *(int32_t *)pValue, *((int32_t *)pValue + 1));
        break;

    case EQ_PARAM_BAND_LEVEL:
        param2 = *pParam;
        if (param2 >= kNumBands) {
            status = -EINVAL;
            break;
        }
        *(int16_t *)pValue = (int16_t)pEqualizer->getGain(param2);
        ALOGV("Equalizer_getParameter() EQ_PARAM_BAND_LEVEL band %d, level %d",
             param2, *(int32_t *)pValue);
        break;

    case EQ_PARAM_CENTER_FREQ:
        param2 = *pParam;
        if (param2 >= kNumBands) {
            status = -EINVAL;
            break;
        }
        *(int32_t *)pValue = pEqualizer->getFrequency(param2);
        ALOGV("Equalizer_getParameter() EQ_PARAM_CENTER_FREQ band %d, frequency %d",
             param2, *(int32_t *)pValue);
        break;

    case EQ_PARAM_BAND_FREQ_RANGE:
        param2 = *pParam;
        if (param2 >= kNumBands) {
            status = -EINVAL;
            break;
        }
        pEqualizer->getBandRange(param2, *(uint32_t *)pValue, *((uint32_t *)pValue + 1));
        ALOGV("Equalizer_getParameter() EQ_PARAM_BAND_FREQ_RANGE band %d, min %d, max %d",
             param2, *(int32_t *)pValue, *((int32_t *)pValue + 1));
        break;

    case EQ_PARAM_GET_BAND:
        param2 = *pParam;
        *(uint16_t *)pValue = (uint16_t)pEqualizer->getMostRelevantBand(param2);
        ALOGV("Equalizer_getParameter() EQ_PARAM_GET_BAND frequency %d, band %d",
             param2, *(int32_t *)pValue);
        break;

    case EQ_PARAM_CUR_PRESET:
        *(uint16_t *)pValue = (uint16_t)pEqualizer->getPreset();
        ALOGV("Equalizer_getParameter() EQ_PARAM_CUR_PRESET %d", *(int32_t *)pValue);
        break;

    case EQ_PARAM_GET_NUM_OF_PRESETS:
        *(uint16_t *)pValue = (uint16_t)pEqualizer->getNumPresets();
        ALOGV("Equalizer_getParameter() EQ_PARAM_GET_NUM_OF_PRESETS %d", *(int16_t *)pValue);
        break;

    case EQ_PARAM_GET_PRESET_NAME:
        param2 = *pParam;
        if (param2 >= pEqualizer->getNumPresets()) {
            status = -EINVAL;
            break;
        }
        name = (char *)pValue;
        strncpy(name, pEqualizer->getPresetName(param2), *pValueSize - 1);
        name[*pValueSize - 1] = 0;
        *pValueSize = strlen(name) + 1;
        ALOGV("Equalizer_getParameter() EQ_PARAM_GET_PRESET_NAME preset %d, name %s len %d",
             param2, gEqualizerPresets[param2].name, *pValueSize);
        break;

    case EQ_PARAM_PROPERTIES: {
        int16_t *p = (int16_t *)pValue;
        ALOGV("Equalizer_getParameter() EQ_PARAM_PROPERTIES");
        p[0] = (int16_t)pEqualizer->getPreset();
        p[1] = (int16_t)kNumBands;
        for (int i = 0; i < kNumBands; i++) {
            p[2 + i] = (int16_t)pEqualizer->getGain(i);
        }
    } break;

    default:
        ALOGV("Equalizer_getParameter() invalid param %d", param);
        status = -EINVAL;
        break;
    }

    return status;
} // end Equalizer_getParameter


//----------------------------------------------------------------------------
// Equalizer_setParameter()
//----------------------------------------------------------------------------
// Purpose:
// Set a Equalizer parameter
//
// Inputs:
//  pEqualizer       - handle to instance data
//  pParam           - pointer to parameter
//  pValue           - pointer to value
//
// Outputs:
//
//
// Side Effects:
//
//----------------------------------------------------------------------------

int Equalizer_setParameter (AudioEqualizer * pEqualizer, int32_t *pParam, void *pValue)
{
    int status = 0;
    int32_t preset;
    int32_t band;
    int32_t level;
    int32_t param = *pParam++;


    switch (param) {
    case EQ_PARAM_CUR_PRESET:
        preset = (int32_t)(*(uint16_t *)pValue);

        ALOGV("setParameter() EQ_PARAM_CUR_PRESET %d", preset);
        if (preset < 0 || preset >= pEqualizer->getNumPresets()) {
            status = -EINVAL;
            break;
        }
        pEqualizer->setPreset(preset);
        pEqualizer->commit(true);
        break;
    case EQ_PARAM_BAND_LEVEL:
        band =  *pParam;
        level = (int32_t)(*(int16_t *)pValue);
        ALOGV("setParameter() EQ_PARAM_BAND_LEVEL band %d, level %d", band, level);
        if (band >= kNumBands) {
            status = -EINVAL;
            break;
        }
        pEqualizer->setGain(band, level);
        pEqualizer->commit(true);
       break;
    case EQ_PARAM_PROPERTIES: {
        ALOGV("setParameter() EQ_PARAM_PROPERTIES");
        int16_t *p = (int16_t *)pValue;
        if ((int)p[0] >= pEqualizer->getNumPresets()) {
            status = -EINVAL;
            break;
        }
        if (p[0] >= 0) {
            pEqualizer->setPreset((int)p[0]);
        } else {
            if ((int)p[1] != kNumBands) {
                status = -EINVAL;
                break;
            }
            for (int i = 0; i < kNumBands; i++) {
                pEqualizer->setGain(i, (int32_t)p[2 + i]);
            }
        }
        pEqualizer->commit(true);
    } break;
    default:
        ALOGV("setParameter() invalid param %d", param);
        status = -EINVAL;
        break;
    }

    return status;
} // end Equalizer_setParameter

} // namespace
} // namespace


//
//--- Effect Control Interface Implementation
//

extern "C" int Equalizer_process(effect_handle_t self, audio_buffer_t *inBuffer, audio_buffer_t *outBuffer)
{
    android::EqualizerContext * pContext = (android::EqualizerContext *) self;

    if (pContext == NULL) {
        return -EINVAL;
    }
    if (inBuffer == NULL || inBuffer->raw == NULL ||
        outBuffer == NULL || outBuffer->raw == NULL ||
        inBuffer->frameCount != outBuffer->frameCount) {
        return -EINVAL;
    }

    if (pContext->state == EQUALIZER_STATE_UNINITIALIZED) {
        return -EINVAL;
    }
    if (pContext->state == EQUALIZER_STATE_INITIALIZED) {
        return -ENODATA;
    }

    pContext->adapter.process(inBuffer->raw, outBuffer->raw, outBuffer->frameCount);

    return 0;
}   // end Equalizer_process

extern "C" int Equalizer_command(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize,
        void *pCmdData, uint32_t *replySize, void *pReplyData) {

    android::EqualizerContext * pContext = (android::EqualizerContext *) self;
    int retsize;

    if (pContext == NULL || pContext->state == EQUALIZER_STATE_UNINITIALIZED) {
        return -EINVAL;
    }

    android::AudioEqualizer * pEqualizer = pContext->pEqualizer;

    ALOGV("Equalizer_command command %d cmdSize %d",cmdCode, cmdSize);

    switch (cmdCode) {
    case EFFECT_CMD_INIT:
        if (pReplyData == NULL || *replySize != sizeof(int)) {
            return -EINVAL;
        }
        *(int *) pReplyData = Equalizer_init(pContext);
        break;
    case EFFECT_CMD_SET_CONFIG:
        if (pCmdData == NULL || cmdSize != sizeof(effect_config_t)
                || pReplyData == NULL || *replySize != sizeof(int)) {
            return -EINVAL;
        }
        *(int *) pReplyData = Equalizer_setConfig(pContext,
                (effect_config_t *) pCmdData);
        break;
    case EFFECT_CMD_GET_CONFIG:
        if (pReplyData == NULL || *replySize != sizeof(effect_config_t)) {
            return -EINVAL;
        }
        Equalizer_getConfig(pContext, (effect_config_t *) pCmdData);
        break;
    case EFFECT_CMD_RESET:
        Equalizer_setConfig(pContext, &pContext->config);
        break;
    case EFFECT_CMD_GET_PARAM: {
        if (pCmdData == NULL || cmdSize < (int)(sizeof(effect_param_t) + sizeof(int32_t)) ||
            pReplyData == NULL || *replySize < (int) (sizeof(effect_param_t) + sizeof(int32_t))) {
            return -EINVAL;
        }
        effect_param_t *p = (effect_param_t *)pCmdData;
        memcpy(pReplyData, pCmdData, sizeof(effect_param_t) + p->psize);
        p = (effect_param_t *)pReplyData;
        int voffset = ((p->psize - 1) / sizeof(int32_t) + 1) * sizeof(int32_t);
        p->status = android::Equalizer_getParameter(pEqualizer, (int32_t *)p->data, &p->vsize,
                p->data + voffset);
        *replySize = sizeof(effect_param_t) + voffset + p->vsize;
        ALOGV("Equalizer_command EFFECT_CMD_GET_PARAM *pCmdData %d, *replySize %d, *pReplyData %08x %08x",
                *(int32_t *)((char *)pCmdData + sizeof(effect_param_t)), *replySize,
                *(int32_t *)((char *)pReplyData + sizeof(effect_param_t) + voffset),
                *(int32_t *)((char *)pReplyData + sizeof(effect_param_t) + voffset + sizeof(int32_t)));

        } break;
    case EFFECT_CMD_SET_PARAM: {
        ALOGV("Equalizer_command EFFECT_CMD_SET_PARAM cmdSize %d pCmdData %p, *replySize %d, pReplyData %p",
             cmdSize, pCmdData, *replySize, pReplyData);
        if (pCmdData == NULL || cmdSize < (int)(sizeof(effect_param_t) + sizeof(int32_t)) ||
            pReplyData == NULL || *replySize != sizeof(int32_t)) {
            return -EINVAL;
        }
        effect_param_t *p = (effect_param_t *) pCmdData;
        *(int *)pReplyData = android::Equalizer_setParameter(pEqualizer, (int32_t *)p->data,
                p->data + p->psize);
        } break;
    case EFFECT_CMD_ENABLE:
        if (pReplyData == NULL || *replySize != sizeof(int)) {
            return -EINVAL;
        }
        if (pContext->state != EQUALIZER_STATE_INITIALIZED) {
            return -ENOSYS;
        }
        pContext->state = EQUALIZER_STATE_ACTIVE;
        ALOGV("EFFECT_CMD_ENABLE() OK");
        *(int *)pReplyData = 0;
        break;
    case EFFECT_CMD_DISABLE:
        if (pReplyData == NULL || *replySize != sizeof(int)) {
            return -EINVAL;
        }
        if (pContext->state != EQUALIZER_STATE_ACTIVE) {
            return -ENOSYS;
        }
        pContext->state = EQUALIZER_STATE_INITIALIZED;
        ALOGV("EFFECT_CMD_DISABLE() OK");
        *(int *)pReplyData = 0;
        break;
    case EFFECT_CMD_SET_DEVICE:
    case EFFECT_CMD_SET_VOLUME:
    case EFFECT_CMD_SET_AUDIO_MODE:
        break;
    default:
        ALOGW("Equalizer_command invalid command %d",cmdCode);
        return -EINVAL;
    }

    return 0;
}

extern "C" int Equalizer_getDescriptor(effect_handle_t   self,
                                    effect_descriptor_t *pDescriptor)
{
    android::EqualizerContext * pContext = (android::EqualizerContext *) self;

    if (pContext == NULL || pDescriptor == NULL) {
        ALOGV("Equalizer_getDescriptor() invalid param");
        return -EINVAL;
    }

    memcpy(pDescriptor, &android::gEqualizerDescriptor, sizeof(effect_descriptor_t));

    return 0;
}

// effect_handle_t interface implementation for equalizer effect
const struct effect_interface_s gEqualizerInterface = {
        Equalizer_process,
        Equalizer_command,
        Equalizer_getDescriptor,
        NULL
};


audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
    tag : AUDIO_EFFECT_LIBRARY_TAG,
    version : EFFECT_LIBRARY_API_VERSION,
    name : "Test Equalizer Library",
    implementor : "The Android Open Source Project",
    query_num_effects : android::EffectQueryNumberEffects,
    query_effect : android::EffectQueryEffect,
    create_effect : android::EffectCreate,
    release_effect : android::EffectRelease,
    get_descriptor : android::EffectGetDescriptor,
};
