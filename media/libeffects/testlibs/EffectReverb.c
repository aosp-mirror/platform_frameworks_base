/*
 * Copyright (C) 2008 The Android Open Source Project
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

#define LOG_TAG "EffectReverb"
//#define LOG_NDEBUG 0
#include <cutils/log.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include "EffectReverb.h"
#include "EffectsMath.h"

// effect_handle_t interface implementation for reverb effect
const struct effect_interface_s gReverbInterface = {
        Reverb_Process,
        Reverb_Command,
        Reverb_GetDescriptor,
        NULL
};

// Google auxiliary environmental reverb UUID: 1f0ae2e0-4ef7-11df-bc09-0002a5d5c51b
static const effect_descriptor_t gAuxEnvReverbDescriptor = {
        {0xc2e5d5f0, 0x94bd, 0x4763, 0x9cac, {0x4e, 0x23, 0x4d, 0x06, 0x83, 0x9e}},
        {0x1f0ae2e0, 0x4ef7, 0x11df, 0xbc09, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}},
        EFFECT_CONTROL_API_VERSION,
        // flags other than EFFECT_FLAG_TYPE_AUXILIARY set for test purpose
        EFFECT_FLAG_TYPE_AUXILIARY | EFFECT_FLAG_DEVICE_IND | EFFECT_FLAG_AUDIO_MODE_IND,
        0, // TODO
        33,
        "Aux Environmental Reverb",
        "The Android Open Source Project"
};

// Google insert environmental reverb UUID: aa476040-6342-11df-91a4-0002a5d5c51b
static const effect_descriptor_t gInsertEnvReverbDescriptor = {
        {0xc2e5d5f0, 0x94bd, 0x4763, 0x9cac, {0x4e, 0x23, 0x4d, 0x06, 0x83, 0x9e}},
        {0xaa476040, 0x6342, 0x11df, 0x91a4, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}},
        EFFECT_CONTROL_API_VERSION,
        EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_FIRST,
        0, // TODO
        33,
        "Insert Environmental reverb",
        "The Android Open Source Project"
};

// Google auxiliary preset reverb UUID: 63909320-53a6-11df-bdbd-0002a5d5c51b
static const effect_descriptor_t gAuxPresetReverbDescriptor = {
        {0x47382d60, 0xddd8, 0x11db, 0xbf3a, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}},
        {0x63909320, 0x53a6, 0x11df, 0xbdbd, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}},
        EFFECT_CONTROL_API_VERSION,
        EFFECT_FLAG_TYPE_AUXILIARY,
        0, // TODO
        33,
        "Aux Preset Reverb",
        "The Android Open Source Project"
};

// Google insert preset reverb UUID: d93dc6a0-6342-11df-b128-0002a5d5c51b
static const effect_descriptor_t gInsertPresetReverbDescriptor = {
        {0x47382d60, 0xddd8, 0x11db, 0xbf3a, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}},
        {0xd93dc6a0, 0x6342, 0x11df, 0xb128, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}},
        EFFECT_CONTROL_API_VERSION,
        EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_FIRST,
        0, // TODO
        33,
        "Insert Preset Reverb",
        "The Android Open Source Project"
};

// gDescriptors contains pointers to all defined effect descriptor in this library
static const effect_descriptor_t * const gDescriptors[] = {
        &gAuxEnvReverbDescriptor,
        &gInsertEnvReverbDescriptor,
        &gAuxPresetReverbDescriptor,
        &gInsertPresetReverbDescriptor
};

/*----------------------------------------------------------------------------
 * Effect API implementation
 *--------------------------------------------------------------------------*/

/*--- Effect Library Interface Implementation ---*/

int EffectQueryNumberEffects(uint32_t *pNumEffects) {
    *pNumEffects = sizeof(gDescriptors) / sizeof(const effect_descriptor_t *);
    return 0;
}

int EffectQueryEffect(uint32_t index, effect_descriptor_t *pDescriptor) {
    if (pDescriptor == NULL) {
        return -EINVAL;
    }
    if (index >= sizeof(gDescriptors) / sizeof(const effect_descriptor_t *)) {
        return -EINVAL;
    }
    memcpy(pDescriptor, gDescriptors[index],
            sizeof(effect_descriptor_t));
    return 0;
}

int EffectCreate(effect_uuid_t *uuid,
        int32_t sessionId,
        int32_t ioId,
        effect_handle_t *pHandle) {
    int ret;
    int i;
    reverb_module_t *module;
    const effect_descriptor_t *desc;
    int aux = 0;
    int preset = 0;

    ALOGV("EffectLibCreateEffect start");

    if (pHandle == NULL || uuid == NULL) {
        return -EINVAL;
    }

    for (i = 0; gDescriptors[i] != NULL; i++) {
        desc = gDescriptors[i];
        if (memcmp(uuid, &desc->uuid, sizeof(effect_uuid_t))
                == 0) {
            break;
        }
    }

    if (gDescriptors[i] == NULL) {
        return -ENOENT;
    }

    module = malloc(sizeof(reverb_module_t));

    module->itfe = &gReverbInterface;

    module->context.mState = REVERB_STATE_UNINITIALIZED;

    if (memcmp(&desc->type, SL_IID_PRESETREVERB, sizeof(effect_uuid_t)) == 0) {
        preset = 1;
    }
    if ((desc->flags & EFFECT_FLAG_TYPE_MASK) == EFFECT_FLAG_TYPE_AUXILIARY) {
        aux = 1;
    }
    ret = Reverb_Init(module, aux, preset);
    if (ret < 0) {
        LOGW("EffectLibCreateEffect() init failed");
        free(module);
        return ret;
    }

    *pHandle = (effect_handle_t) module;

    module->context.mState = REVERB_STATE_INITIALIZED;

    ALOGV("EffectLibCreateEffect %p ,size %d", module, sizeof(reverb_module_t));

    return 0;
}

int EffectRelease(effect_handle_t handle) {
    reverb_module_t *pRvbModule = (reverb_module_t *)handle;

    ALOGV("EffectLibReleaseEffect %p", handle);
    if (handle == NULL) {
        return -EINVAL;
    }

    pRvbModule->context.mState = REVERB_STATE_UNINITIALIZED;

    free(pRvbModule);
    return 0;
}

int EffectGetDescriptor(effect_uuid_t       *uuid,
                        effect_descriptor_t *pDescriptor) {
    int i;
    int length = sizeof(gDescriptors) / sizeof(const effect_descriptor_t *);

    if (pDescriptor == NULL || uuid == NULL){
        ALOGV("EffectGetDescriptor() called with NULL pointer");
        return -EINVAL;
    }

    for (i = 0; i < length; i++) {
        if (memcmp(uuid, &gDescriptors[i]->uuid, sizeof(effect_uuid_t)) == 0) {
            memcpy(pDescriptor, gDescriptors[i], sizeof(effect_descriptor_t));
            ALOGV("EffectGetDescriptor - UUID matched Reverb type %d, UUID = %x",
                 i, gDescriptors[i]->uuid.timeLow);
            return 0;
        }
    }

    return -EINVAL;
} /* end EffectGetDescriptor */

/*--- Effect Control Interface Implementation ---*/

static int Reverb_Process(effect_handle_t self, audio_buffer_t *inBuffer, audio_buffer_t *outBuffer) {
    reverb_object_t *pReverb;
    int16_t *pSrc, *pDst;
    reverb_module_t *pRvbModule = (reverb_module_t *)self;

    if (pRvbModule == NULL) {
        return -EINVAL;
    }

    if (inBuffer == NULL || inBuffer->raw == NULL ||
        outBuffer == NULL || outBuffer->raw == NULL ||
        inBuffer->frameCount != outBuffer->frameCount) {
        return -EINVAL;
    }

    pReverb = (reverb_object_t*) &pRvbModule->context;

    if (pReverb->mState == REVERB_STATE_UNINITIALIZED) {
        return -EINVAL;
    }
    if (pReverb->mState == REVERB_STATE_INITIALIZED) {
        return -ENODATA;
    }

    //if bypassed or the preset forces the signal to be completely dry
    if (pReverb->m_bBypass != 0) {
        if (inBuffer->raw != outBuffer->raw) {
            int16_t smp;
            pSrc = inBuffer->s16;
            pDst = outBuffer->s16;
            size_t count = inBuffer->frameCount;
            if (pRvbModule->config.inputCfg.channels == pRvbModule->config.outputCfg.channels) {
                count *= 2;
                while (count--) {
                    *pDst++ = *pSrc++;
                }
            } else {
                while (count--) {
                    smp = *pSrc++;
                    *pDst++ = smp;
                    *pDst++ = smp;
                }
            }
        }
        return 0;
    }

    if (pReverb->m_nNextRoom != pReverb->m_nCurrentRoom) {
        ReverbUpdateRoom(pReverb, true);
    }

    pSrc = inBuffer->s16;
    pDst = outBuffer->s16;
    size_t numSamples = outBuffer->frameCount;
    while (numSamples) {
        uint32_t processedSamples;
        if (numSamples > (uint32_t) pReverb->m_nUpdatePeriodInSamples) {
            processedSamples = (uint32_t) pReverb->m_nUpdatePeriodInSamples;
        } else {
            processedSamples = numSamples;
        }

        /* increment update counter */
        pReverb->m_nUpdateCounter += (int16_t) processedSamples;
        /* check if update counter needs to be reset */
        if (pReverb->m_nUpdateCounter >= pReverb->m_nUpdatePeriodInSamples) {
            /* update interval has elapsed, so reset counter */
            pReverb->m_nUpdateCounter -= pReverb->m_nUpdatePeriodInSamples;
            ReverbUpdateXfade(pReverb, pReverb->m_nUpdatePeriodInSamples);

        } /* end if m_nUpdateCounter >= update interval */

        Reverb(pReverb, processedSamples, pDst, pSrc);

        numSamples -= processedSamples;
        if (pReverb->m_Aux) {
            pSrc += processedSamples;
        } else {
            pSrc += processedSamples * NUM_OUTPUT_CHANNELS;
        }
        pDst += processedSamples * NUM_OUTPUT_CHANNELS;
    }

    return 0;
}


static int Reverb_Command(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize,
        void *pCmdData, uint32_t *replySize, void *pReplyData) {
    reverb_module_t *pRvbModule = (reverb_module_t *) self;
    reverb_object_t *pReverb;
    int retsize;

    if (pRvbModule == NULL ||
            pRvbModule->context.mState == REVERB_STATE_UNINITIALIZED) {
        return -EINVAL;
    }

    pReverb = (reverb_object_t*) &pRvbModule->context;

    ALOGV("Reverb_Command command %d cmdSize %d",cmdCode, cmdSize);

    switch (cmdCode) {
    case EFFECT_CMD_INIT:
        if (pReplyData == NULL || *replySize != sizeof(int)) {
            return -EINVAL;
        }
        *(int *) pReplyData = Reverb_Init(pRvbModule, pReverb->m_Aux, pReverb->m_Preset);
        if (*(int *) pReplyData == 0) {
            pRvbModule->context.mState = REVERB_STATE_INITIALIZED;
        }
        break;
    case EFFECT_CMD_SET_CONFIG:
        if (pCmdData == NULL || cmdSize != sizeof(effect_config_t)
                || pReplyData == NULL || *replySize != sizeof(int)) {
            return -EINVAL;
        }
        *(int *) pReplyData = Reverb_setConfig(pRvbModule,
                (effect_config_t *)pCmdData, false);
        break;
    case EFFECT_CMD_GET_CONFIG:
        if (pReplyData == NULL || *replySize != sizeof(effect_config_t)) {
            return -EINVAL;
        }
        Reverb_getConfig(pRvbModule, (effect_config_t *) pCmdData);
        break;
    case EFFECT_CMD_RESET:
        Reverb_Reset(pReverb, false);
        break;
    case EFFECT_CMD_GET_PARAM:
        ALOGV("Reverb_Command EFFECT_CMD_GET_PARAM pCmdData %p, *replySize %d, pReplyData: %p",pCmdData, *replySize, pReplyData);

        if (pCmdData == NULL || cmdSize < (int)(sizeof(effect_param_t) + sizeof(int32_t)) ||
            pReplyData == NULL || *replySize < (int) sizeof(effect_param_t)) {
            return -EINVAL;
        }
        effect_param_t *rep = (effect_param_t *) pReplyData;
        memcpy(pReplyData, pCmdData, sizeof(effect_param_t) + sizeof(int32_t));
        ALOGV("Reverb_Command EFFECT_CMD_GET_PARAM param %d, replySize %d",*(int32_t *)rep->data, rep->vsize);
        rep->status = Reverb_getParameter(pReverb, *(int32_t *)rep->data, &rep->vsize,
                rep->data + sizeof(int32_t));
        *replySize = sizeof(effect_param_t) + sizeof(int32_t) + rep->vsize;
        break;
    case EFFECT_CMD_SET_PARAM:
        ALOGV("Reverb_Command EFFECT_CMD_SET_PARAM cmdSize %d pCmdData %p, *replySize %d, pReplyData %p",
                cmdSize, pCmdData, *replySize, pReplyData);
        if (pCmdData == NULL || (cmdSize < (int)(sizeof(effect_param_t) + sizeof(int32_t)))
                || pReplyData == NULL || *replySize != (int)sizeof(int32_t)) {
            return -EINVAL;
        }
        effect_param_t *cmd = (effect_param_t *) pCmdData;
        *(int *)pReplyData = Reverb_setParameter(pReverb, *(int32_t *)cmd->data,
                cmd->vsize, cmd->data + sizeof(int32_t));
        break;
    case EFFECT_CMD_ENABLE:
        if (pReplyData == NULL || *replySize != sizeof(int)) {
            return -EINVAL;
        }
        if (pReverb->mState != REVERB_STATE_INITIALIZED) {
            return -ENOSYS;
        }
        pReverb->mState = REVERB_STATE_ACTIVE;
        ALOGV("EFFECT_CMD_ENABLE() OK");
        *(int *)pReplyData = 0;
        break;
    case EFFECT_CMD_DISABLE:
        if (pReplyData == NULL || *replySize != sizeof(int)) {
            return -EINVAL;
        }
        if (pReverb->mState != REVERB_STATE_ACTIVE) {
            return -ENOSYS;
        }
        pReverb->mState = REVERB_STATE_INITIALIZED;
        ALOGV("EFFECT_CMD_DISABLE() OK");
        *(int *)pReplyData = 0;
        break;
    case EFFECT_CMD_SET_DEVICE:
        if (pCmdData == NULL || cmdSize != (int)sizeof(uint32_t)) {
            return -EINVAL;
        }
        ALOGV("Reverb_Command EFFECT_CMD_SET_DEVICE: 0x%08x", *(uint32_t *)pCmdData);
        break;
    case EFFECT_CMD_SET_VOLUME: {
        // audio output is always stereo => 2 channel volumes
        if (pCmdData == NULL || cmdSize != (int)sizeof(uint32_t) * 2) {
            return -EINVAL;
        }
        float left = (float)(*(uint32_t *)pCmdData) / (1 << 24);
        float right = (float)(*((uint32_t *)pCmdData + 1)) / (1 << 24);
        ALOGV("Reverb_Command EFFECT_CMD_SET_VOLUME: left %f, right %f ", left, right);
        break;
        }
    case EFFECT_CMD_SET_AUDIO_MODE:
        if (pCmdData == NULL || cmdSize != (int)sizeof(uint32_t)) {
            return -EINVAL;
        }
        ALOGV("Reverb_Command EFFECT_CMD_SET_AUDIO_MODE: %d", *(uint32_t *)pCmdData);
        break;
    default:
        LOGW("Reverb_Command invalid command %d",cmdCode);
        return -EINVAL;
    }

    return 0;
}

int Reverb_GetDescriptor(effect_handle_t   self,
                                    effect_descriptor_t *pDescriptor)
{
    reverb_module_t *pRvbModule = (reverb_module_t *) self;
    reverb_object_t *pReverb;
    const effect_descriptor_t *desc;

    if (pRvbModule == NULL ||
            pRvbModule->context.mState == REVERB_STATE_UNINITIALIZED) {
        return -EINVAL;
    }

    pReverb = (reverb_object_t*) &pRvbModule->context;

    if (pReverb->m_Aux) {
        if (pReverb->m_Preset) {
            desc = &gAuxPresetReverbDescriptor;
        } else {
            desc = &gAuxEnvReverbDescriptor;
        }
    } else {
        if (pReverb->m_Preset) {
            desc = &gInsertPresetReverbDescriptor;
        } else {
            desc = &gInsertEnvReverbDescriptor;
        }
    }

    memcpy(pDescriptor, desc, sizeof(effect_descriptor_t));

    return 0;
}   /* end Reverb_getDescriptor */

/*----------------------------------------------------------------------------
 * Reverb internal functions
 *--------------------------------------------------------------------------*/

/*----------------------------------------------------------------------------
 * Reverb_Init()
 *----------------------------------------------------------------------------
 * Purpose:
 * Initialize reverb context and apply default parameters
 *
 * Inputs:
 *  pRvbModule    - pointer to reverb effect module
 *  aux           - indicates if the reverb is used as auxiliary (1) or insert (0)
 *  preset        - indicates if the reverb is used in preset (1) or environmental (0) mode
 *
 * Outputs:
 *
 * Side Effects:
 *
 *----------------------------------------------------------------------------
 */

int Reverb_Init(reverb_module_t *pRvbModule, int aux, int preset) {
    int ret;

    ALOGV("Reverb_Init module %p, aux: %d, preset: %d", pRvbModule,aux, preset);

    memset(&pRvbModule->context, 0, sizeof(reverb_object_t));

    pRvbModule->context.m_Aux = (uint16_t)aux;
    pRvbModule->context.m_Preset = (uint16_t)preset;

    pRvbModule->config.inputCfg.samplingRate = 44100;
    if (aux) {
        pRvbModule->config.inputCfg.channels = AUDIO_CHANNEL_OUT_MONO;
    } else {
        pRvbModule->config.inputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    }
    pRvbModule->config.inputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
    pRvbModule->config.inputCfg.bufferProvider.getBuffer = NULL;
    pRvbModule->config.inputCfg.bufferProvider.releaseBuffer = NULL;
    pRvbModule->config.inputCfg.bufferProvider.cookie = NULL;
    pRvbModule->config.inputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
    pRvbModule->config.inputCfg.mask = EFFECT_CONFIG_ALL;
    pRvbModule->config.outputCfg.samplingRate = 44100;
    pRvbModule->config.outputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    pRvbModule->config.outputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
    pRvbModule->config.outputCfg.bufferProvider.getBuffer = NULL;
    pRvbModule->config.outputCfg.bufferProvider.releaseBuffer = NULL;
    pRvbModule->config.outputCfg.bufferProvider.cookie = NULL;
    pRvbModule->config.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_ACCUMULATE;
    pRvbModule->config.outputCfg.mask = EFFECT_CONFIG_ALL;

    ret = Reverb_setConfig(pRvbModule, &pRvbModule->config, true);
    if (ret < 0) {
        ALOGV("Reverb_Init error %d on module %p", ret, pRvbModule);
    }

    return ret;
}

/*----------------------------------------------------------------------------
 * Reverb_setConfig()
 *----------------------------------------------------------------------------
 * Purpose:
 *  Set input and output audio configuration.
 *
 * Inputs:
 *  pRvbModule    - pointer to reverb effect module
 *  pConfig       - pointer to effect_config_t structure containing input
 *              and output audio parameters configuration
 *  init          - true if called from init function
 * Outputs:
 *
 * Side Effects:
 *
 *----------------------------------------------------------------------------
 */

int Reverb_setConfig(reverb_module_t *pRvbModule, effect_config_t *pConfig,
        bool init) {
    reverb_object_t *pReverb = &pRvbModule->context;
    int bufferSizeInSamples;
    int updatePeriodInSamples;
    int xfadePeriodInSamples;

    // Check configuration compatibility with build options
    if (pConfig->inputCfg.samplingRate
        != pConfig->outputCfg.samplingRate
        || pConfig->outputCfg.channels != OUTPUT_CHANNELS
        || pConfig->inputCfg.format != AUDIO_FORMAT_PCM_16_BIT
        || pConfig->outputCfg.format != AUDIO_FORMAT_PCM_16_BIT) {
        ALOGV("Reverb_setConfig invalid config");
        return -EINVAL;
    }
    if ((pReverb->m_Aux && (pConfig->inputCfg.channels != AUDIO_CHANNEL_OUT_MONO)) ||
        (!pReverb->m_Aux && (pConfig->inputCfg.channels != AUDIO_CHANNEL_OUT_STEREO))) {
        ALOGV("Reverb_setConfig invalid config");
        return -EINVAL;
    }

    memcpy(&pRvbModule->config, pConfig, sizeof(effect_config_t));

    pReverb->m_nSamplingRate = pRvbModule->config.outputCfg.samplingRate;

    switch (pReverb->m_nSamplingRate) {
    case 8000:
        pReverb->m_nUpdatePeriodInBits = 5;
        bufferSizeInSamples = 4096;
        pReverb->m_nCosWT_5KHz = -23170;
        break;
    case 16000:
        pReverb->m_nUpdatePeriodInBits = 6;
        bufferSizeInSamples = 8192;
        pReverb->m_nCosWT_5KHz = -12540;
        break;
    case 22050:
        pReverb->m_nUpdatePeriodInBits = 7;
        bufferSizeInSamples = 8192;
        pReverb->m_nCosWT_5KHz = 4768;
        break;
    case 32000:
        pReverb->m_nUpdatePeriodInBits = 7;
        bufferSizeInSamples = 16384;
        pReverb->m_nCosWT_5KHz = 18205;
        break;
    case 44100:
        pReverb->m_nUpdatePeriodInBits = 8;
        bufferSizeInSamples = 16384;
        pReverb->m_nCosWT_5KHz = 24799;
        break;
    case 48000:
        pReverb->m_nUpdatePeriodInBits = 8;
        bufferSizeInSamples = 16384;
        pReverb->m_nCosWT_5KHz = 25997;
        break;
    default:
        ALOGV("Reverb_setConfig invalid sampling rate %d", pReverb->m_nSamplingRate);
        return -EINVAL;
    }

    // Define a mask for circular addressing, so that array index
    // can wraparound and stay in array boundary of 0, 1, ..., (buffer size -1)
    // The buffer size MUST be a power of two
    pReverb->m_nBufferMask = (int32_t) (bufferSizeInSamples - 1);
    /* reverb parameters are updated every 2^(pReverb->m_nUpdatePeriodInBits) samples */
    updatePeriodInSamples = (int32_t) (0x1L << pReverb->m_nUpdatePeriodInBits);
    /*
     calculate the update counter by bitwise ANDING with this value to
     generate a 2^n modulo value
     */
    pReverb->m_nUpdatePeriodInSamples = (int32_t) updatePeriodInSamples;

    xfadePeriodInSamples = (int32_t) (REVERB_XFADE_PERIOD_IN_SECONDS
            * (double) pReverb->m_nSamplingRate);

    // set xfade parameters
    pReverb->m_nPhaseIncrement
            = (int16_t) (65536 / ((int16_t) xfadePeriodInSamples
                    / (int16_t) updatePeriodInSamples));

    if (init) {
        ReverbReadInPresets(pReverb);

        // for debugging purposes, allow noise generator
        pReverb->m_bUseNoise = true;

        // for debugging purposes, allow bypass
        pReverb->m_bBypass = 0;

        pReverb->m_nNextRoom = 1;

        pReverb->m_nNoise = (int16_t) 0xABCD;
    }

    Reverb_Reset(pReverb, init);

    return 0;
}

/*----------------------------------------------------------------------------
 * Reverb_getConfig()
 *----------------------------------------------------------------------------
 * Purpose:
 *  Get input and output audio configuration.
 *
 * Inputs:
 *  pRvbModule    - pointer to reverb effect module
 *  pConfig       - pointer to effect_config_t structure containing input
 *              and output audio parameters configuration
 * Outputs:
 *
 * Side Effects:
 *
 *----------------------------------------------------------------------------
 */

void Reverb_getConfig(reverb_module_t *pRvbModule, effect_config_t *pConfig)
{
    memcpy(pConfig, &pRvbModule->config, sizeof(effect_config_t));
}

/*----------------------------------------------------------------------------
 * Reverb_Reset()
 *----------------------------------------------------------------------------
 * Purpose:
 *  Reset internal states and clear delay lines.
 *
 * Inputs:
 *  pReverb    - pointer to reverb context
 *  init       - true if called from init function
 *
 * Outputs:
 *
 * Side Effects:
 *
 *----------------------------------------------------------------------------
 */

void Reverb_Reset(reverb_object_t *pReverb, bool init) {
    int bufferSizeInSamples = (int32_t) (pReverb->m_nBufferMask + 1);
    int maxApSamples;
    int maxDelaySamples;
    int maxEarlySamples;
    int ap1In;
    int delay0In;
    int delay1In;
    int32_t i;
    uint16_t nOffset;

    maxApSamples = ((int32_t) (MAX_AP_TIME * pReverb->m_nSamplingRate) >> 16);
    maxDelaySamples = ((int32_t) (MAX_DELAY_TIME * pReverb->m_nSamplingRate)
            >> 16);
    maxEarlySamples = ((int32_t) (MAX_EARLY_TIME * pReverb->m_nSamplingRate)
            >> 16);

    ap1In = (AP0_IN + maxApSamples + GUARD);
    delay0In = (ap1In + maxApSamples + GUARD);
    delay1In = (delay0In + maxDelaySamples + GUARD);
    // Define the max offsets for the end points of each section
    // i.e., we don't expect a given section's taps to go beyond
    // the following limits

    pReverb->m_nEarly0in = (delay1In + maxDelaySamples + GUARD);
    pReverb->m_nEarly1in = (pReverb->m_nEarly0in + maxEarlySamples + GUARD);

    pReverb->m_sAp0.m_zApIn = AP0_IN;

    pReverb->m_zD0In = delay0In;

    pReverb->m_sAp1.m_zApIn = ap1In;

    pReverb->m_zD1In = delay1In;

    pReverb->m_zOutLpfL = 0;
    pReverb->m_zOutLpfR = 0;

    pReverb->m_nRevFbkR = 0;
    pReverb->m_nRevFbkL = 0;

    // set base index into circular buffer
    pReverb->m_nBaseIndex = 0;

    // clear the reverb delay line
    for (i = 0; i < bufferSizeInSamples; i++) {
        pReverb->m_nDelayLine[i] = 0;
    }

    ReverbUpdateRoom(pReverb, init);

    pReverb->m_nUpdateCounter = 0;

    pReverb->m_nPhase = -32768;

    pReverb->m_nSin = 0;
    pReverb->m_nCos = 0;
    pReverb->m_nSinIncrement = 0;
    pReverb->m_nCosIncrement = 0;

    // set delay tap lengths
    nOffset = ReverbCalculateNoise(pReverb);

    pReverb->m_zD1Cross = pReverb->m_nDelay1Out - pReverb->m_nMaxExcursion
            + nOffset;

    nOffset = ReverbCalculateNoise(pReverb);

    pReverb->m_zD0Cross = pReverb->m_nDelay0Out - pReverb->m_nMaxExcursion
            - nOffset;

    nOffset = ReverbCalculateNoise(pReverb);

    pReverb->m_zD0Self = pReverb->m_nDelay0Out - pReverb->m_nMaxExcursion
            - nOffset;

    nOffset = ReverbCalculateNoise(pReverb);

    pReverb->m_zD1Self = pReverb->m_nDelay1Out - pReverb->m_nMaxExcursion
            + nOffset;
}

/*----------------------------------------------------------------------------
 * Reverb_getParameter()
 *----------------------------------------------------------------------------
 * Purpose:
 * Get a Reverb parameter
 *
 * Inputs:
 *  pReverb       - handle to instance data
 *  param         - parameter
 *  pValue        - pointer to variable to hold retrieved value
 *  pSize         - pointer to value size: maximum size as input
 *
 * Outputs:
 *  *pValue updated with parameter value
 *  *pSize updated with actual value size
 *
 *
 * Side Effects:
 *
 *----------------------------------------------------------------------------
 */
int Reverb_getParameter(reverb_object_t *pReverb, int32_t param, size_t *pSize,
        void *pValue) {
    int32_t *pValue32;
    int16_t *pValue16;
    t_reverb_settings *pProperties;
    int32_t i;
    int32_t temp;
    int32_t temp2;
    size_t size;

    if (pReverb->m_Preset) {
        if (param != REVERB_PARAM_PRESET || *pSize < sizeof(int16_t)) {
            return -EINVAL;
        }
        size = sizeof(int16_t);
        pValue16 = (int16_t *)pValue;
        // REVERB_PRESET_NONE is mapped to bypass
        if (pReverb->m_bBypass != 0) {
            *pValue16 = (int16_t)REVERB_PRESET_NONE;
        } else {
            *pValue16 = (int16_t)(pReverb->m_nNextRoom + 1);
        }
        ALOGV("get REVERB_PARAM_PRESET, preset %d", *pValue16);
    } else {
        switch (param) {
        case REVERB_PARAM_ROOM_LEVEL:
        case REVERB_PARAM_ROOM_HF_LEVEL:
        case REVERB_PARAM_DECAY_HF_RATIO:
        case REVERB_PARAM_REFLECTIONS_LEVEL:
        case REVERB_PARAM_REVERB_LEVEL:
        case REVERB_PARAM_DIFFUSION:
        case REVERB_PARAM_DENSITY:
            size = sizeof(int16_t);
            break;

        case REVERB_PARAM_BYPASS:
        case REVERB_PARAM_DECAY_TIME:
        case REVERB_PARAM_REFLECTIONS_DELAY:
        case REVERB_PARAM_REVERB_DELAY:
            size = sizeof(int32_t);
            break;

        case REVERB_PARAM_PROPERTIES:
            size = sizeof(t_reverb_settings);
            break;

        default:
            return -EINVAL;
        }

        if (*pSize < size) {
            return -EINVAL;
        }

        pValue32 = (int32_t *) pValue;
        pValue16 = (int16_t *) pValue;
        pProperties = (t_reverb_settings *) pValue;

        switch (param) {
        case REVERB_PARAM_BYPASS:
            *pValue32 = (int32_t) pReverb->m_bBypass;
            break;

        case REVERB_PARAM_PROPERTIES:
            pValue16 = &pProperties->roomLevel;
            /* FALL THROUGH */

        case REVERB_PARAM_ROOM_LEVEL:
            // Convert m_nRoomLpfFwd to millibels
            temp = (pReverb->m_nRoomLpfFwd << 15)
                    / (32767 - pReverb->m_nRoomLpfFbk);
            *pValue16 = Effects_Linear16ToMillibels(temp);

            ALOGV("get REVERB_PARAM_ROOM_LEVEL %d, gain %d, m_nRoomLpfFwd %d, m_nRoomLpfFbk %d", *pValue16, temp, pReverb->m_nRoomLpfFwd, pReverb->m_nRoomLpfFbk);

            if (param == REVERB_PARAM_ROOM_LEVEL) {
                break;
            }
            pValue16 = &pProperties->roomHFLevel;
            /* FALL THROUGH */

        case REVERB_PARAM_ROOM_HF_LEVEL:
            // The ratio between linear gain at 0Hz and at 5000Hz for the room low pass is:
            // (1 + a1) / sqrt(a1^2 + 2*C*a1 + 1) where:
            // - a1 is minus the LP feedback gain: -pReverb->m_nRoomLpfFbk
            // - C is cos(2piWT) @ 5000Hz: pReverb->m_nCosWT_5KHz

            temp = MULT_EG1_EG1(pReverb->m_nRoomLpfFbk, pReverb->m_nRoomLpfFbk);
            ALOGV("get REVERB_PARAM_ROOM_HF_LEVEL, a1^2 %d", temp);
            temp2 = MULT_EG1_EG1(pReverb->m_nRoomLpfFbk, pReverb->m_nCosWT_5KHz)
                    << 1;
            ALOGV("get REVERB_PARAM_ROOM_HF_LEVEL, 2 Cos a1 %d", temp2);
            temp = 32767 + temp - temp2;
            ALOGV("get REVERB_PARAM_ROOM_HF_LEVEL, a1^2 + 2 Cos a1 + 1 %d", temp);
            temp = Effects_Sqrt(temp) * 181;
            ALOGV("get REVERB_PARAM_ROOM_HF_LEVEL, SQRT(a1^2 + 2 Cos a1 + 1) %d", temp);
            temp = ((32767 - pReverb->m_nRoomLpfFbk) << 15) / temp;

            ALOGV("get REVERB_PARAM_ROOM_HF_LEVEL, gain %d, m_nRoomLpfFwd %d, m_nRoomLpfFbk %d", temp, pReverb->m_nRoomLpfFwd, pReverb->m_nRoomLpfFbk);

            *pValue16 = Effects_Linear16ToMillibels(temp);

            if (param == REVERB_PARAM_ROOM_HF_LEVEL) {
                break;
            }
            pValue32 = (int32_t *)&pProperties->decayTime;
            /* FALL THROUGH */

        case REVERB_PARAM_DECAY_TIME:
            // Calculate reverb feedback path gain
            temp = (pReverb->m_nRvbLpfFwd << 15) / (32767 - pReverb->m_nRvbLpfFbk);
            temp = Effects_Linear16ToMillibels(temp);

            // Calculate decay time: g = -6000 d/DT , g gain in millibels, d reverb delay, DT decay time
            temp = (-6000 * pReverb->m_nLateDelay) / temp;

            // Convert samples to ms
            *pValue32 = (temp * 1000) / pReverb->m_nSamplingRate;

            ALOGV("get REVERB_PARAM_DECAY_TIME, samples %d, ms %d", temp, *pValue32);

            if (param == REVERB_PARAM_DECAY_TIME) {
                break;
            }
            pValue16 = &pProperties->decayHFRatio;
            /* FALL THROUGH */

        case REVERB_PARAM_DECAY_HF_RATIO:
            // If r is the decay HF ratio  (r = REVERB_PARAM_DECAY_HF_RATIO/1000) we have:
            //       DT_5000Hz = DT_0Hz * r
            //  and  G_5000Hz = -6000 * d / DT_5000Hz and G_0Hz = -6000 * d / DT_0Hz in millibels so :
            // r = G_0Hz/G_5000Hz in millibels
            // The linear gain at 5000Hz is b0 / sqrt(a1^2 + 2*C*a1 + 1) where:
            // - a1 is minus the LP feedback gain: -pReverb->m_nRvbLpfFbk
            // - b0 is the LP forward gain: pReverb->m_nRvbLpfFwd
            // - C is cos(2piWT) @ 5000Hz: pReverb->m_nCosWT_5KHz
            if (pReverb->m_nRvbLpfFbk == 0) {
                *pValue16 = 1000;
                ALOGV("get REVERB_PARAM_DECAY_HF_RATIO, pReverb->m_nRvbLpfFbk == 0, ratio %d", *pValue16);
            } else {
                temp = MULT_EG1_EG1(pReverb->m_nRvbLpfFbk, pReverb->m_nRvbLpfFbk);
                temp2 = MULT_EG1_EG1(pReverb->m_nRvbLpfFbk, pReverb->m_nCosWT_5KHz)
                        << 1;
                temp = 32767 + temp - temp2;
                temp = Effects_Sqrt(temp) * 181;
                temp = (pReverb->m_nRvbLpfFwd << 15) / temp;
                // The linear gain at 0Hz is b0 / (a1 + 1)
                temp2 = (pReverb->m_nRvbLpfFwd << 15) / (32767
                        - pReverb->m_nRvbLpfFbk);

                temp = Effects_Linear16ToMillibels(temp);
                temp2 = Effects_Linear16ToMillibels(temp2);
                ALOGV("get REVERB_PARAM_DECAY_HF_RATIO, gain 5KHz %d mB, gain DC %d mB", temp, temp2);

                if (temp == 0)
                    temp = 1;
                temp = (int16_t) ((1000 * temp2) / temp);
                if (temp > 1000)
                    temp = 1000;

                *pValue16 = temp;
                ALOGV("get REVERB_PARAM_DECAY_HF_RATIO, ratio %d", *pValue16);
            }

            if (param == REVERB_PARAM_DECAY_HF_RATIO) {
                break;
            }
            pValue16 = &pProperties->reflectionsLevel;
            /* FALL THROUGH */

        case REVERB_PARAM_REFLECTIONS_LEVEL:
            *pValue16 = Effects_Linear16ToMillibels(pReverb->m_nEarlyGain);

            ALOGV("get REVERB_PARAM_REFLECTIONS_LEVEL, %d", *pValue16);
            if (param == REVERB_PARAM_REFLECTIONS_LEVEL) {
                break;
            }
            pValue32 = (int32_t *)&pProperties->reflectionsDelay;
            /* FALL THROUGH */

        case REVERB_PARAM_REFLECTIONS_DELAY:
            // convert samples to ms
            *pValue32 = (pReverb->m_nEarlyDelay * 1000) / pReverb->m_nSamplingRate;

            ALOGV("get REVERB_PARAM_REFLECTIONS_DELAY, samples %d, ms %d", pReverb->m_nEarlyDelay, *pValue32);

            if (param == REVERB_PARAM_REFLECTIONS_DELAY) {
                break;
            }
            pValue16 = &pProperties->reverbLevel;
            /* FALL THROUGH */

        case REVERB_PARAM_REVERB_LEVEL:
            // Convert linear gain to millibels
            *pValue16 = Effects_Linear16ToMillibels(pReverb->m_nLateGain << 2);

            ALOGV("get REVERB_PARAM_REVERB_LEVEL %d", *pValue16);

            if (param == REVERB_PARAM_REVERB_LEVEL) {
                break;
            }
            pValue32 = (int32_t *)&pProperties->reverbDelay;
            /* FALL THROUGH */

        case REVERB_PARAM_REVERB_DELAY:
            // convert samples to ms
            *pValue32 = (pReverb->m_nLateDelay * 1000) / pReverb->m_nSamplingRate;

            ALOGV("get REVERB_PARAM_REVERB_DELAY, samples %d, ms %d", pReverb->m_nLateDelay, *pValue32);

            if (param == REVERB_PARAM_REVERB_DELAY) {
                break;
            }
            pValue16 = &pProperties->diffusion;
            /* FALL THROUGH */

        case REVERB_PARAM_DIFFUSION:
            temp = (int16_t) ((1000 * (pReverb->m_sAp0.m_nApGain - AP0_GAIN_BASE))
                    / AP0_GAIN_RANGE);

            if (temp < 0)
                temp = 0;
            if (temp > 1000)
                temp = 1000;

            *pValue16 = temp;
            ALOGV("get REVERB_PARAM_DIFFUSION, %d, AP0 gain %d", *pValue16, pReverb->m_sAp0.m_nApGain);

            if (param == REVERB_PARAM_DIFFUSION) {
                break;
            }
            pValue16 = &pProperties->density;
            /* FALL THROUGH */

        case REVERB_PARAM_DENSITY:
            // Calculate AP delay in time units
            temp = ((pReverb->m_sAp0.m_zApOut - pReverb->m_sAp0.m_zApIn) << 16)
                    / pReverb->m_nSamplingRate;

            temp = (int16_t) ((1000 * (temp - AP0_TIME_BASE)) / AP0_TIME_RANGE);

            if (temp < 0)
                temp = 0;
            if (temp > 1000)
                temp = 1000;

            *pValue16 = temp;

            ALOGV("get REVERB_PARAM_DENSITY, %d, AP0 delay smps %d", *pValue16, pReverb->m_sAp0.m_zApOut - pReverb->m_sAp0.m_zApIn);
            break;

        default:
            break;
        }
    }

    *pSize = size;

    ALOGV("Reverb_getParameter, context %p, param %d, value %d",
            pReverb, param, *(int *)pValue);

    return 0;
} /* end Reverb_getParameter */

/*----------------------------------------------------------------------------
 * Reverb_setParameter()
 *----------------------------------------------------------------------------
 * Purpose:
 * Set a Reverb parameter
 *
 * Inputs:
 *  pReverb       - handle to instance data
 *  param         - parameter
 *  pValue        - pointer to parameter value
 *  size          - value size
 *
 * Outputs:
 *
 *
 * Side Effects:
 *
 *----------------------------------------------------------------------------
 */
int Reverb_setParameter(reverb_object_t *pReverb, int32_t param, size_t size,
        void *pValue) {
    int32_t value32;
    int16_t value16;
    t_reverb_settings *pProperties;
    int32_t i;
    int32_t temp;
    int32_t temp2;
    reverb_preset_t *pPreset;
    int maxSamples;
    int32_t averageDelay;
    size_t paramSize;

    ALOGV("Reverb_setParameter, context %p, param %d, value16 %d, value32 %d",
            pReverb, param, *(int16_t *)pValue, *(int32_t *)pValue);

    if (pReverb->m_Preset) {
        if (param != REVERB_PARAM_PRESET || size != sizeof(int16_t)) {
            return -EINVAL;
        }
        value16 = *(int16_t *)pValue;
        ALOGV("set REVERB_PARAM_PRESET, preset %d", value16);
        if (value16 < REVERB_PRESET_NONE || value16 > REVERB_PRESET_PLATE) {
            return -EINVAL;
        }
        // REVERB_PRESET_NONE is mapped to bypass
        if (value16 == REVERB_PRESET_NONE) {
            pReverb->m_bBypass = 1;
        } else {
            pReverb->m_bBypass = 0;
            pReverb->m_nNextRoom = value16 - 1;
        }
    } else {
        switch (param) {
        case REVERB_PARAM_ROOM_LEVEL:
        case REVERB_PARAM_ROOM_HF_LEVEL:
        case REVERB_PARAM_DECAY_HF_RATIO:
        case REVERB_PARAM_REFLECTIONS_LEVEL:
        case REVERB_PARAM_REVERB_LEVEL:
        case REVERB_PARAM_DIFFUSION:
        case REVERB_PARAM_DENSITY:
            paramSize = sizeof(int16_t);
            break;

        case REVERB_PARAM_BYPASS:
        case REVERB_PARAM_DECAY_TIME:
        case REVERB_PARAM_REFLECTIONS_DELAY:
        case REVERB_PARAM_REVERB_DELAY:
            paramSize = sizeof(int32_t);
            break;

        case REVERB_PARAM_PROPERTIES:
            paramSize = sizeof(t_reverb_settings);
            break;

        default:
            return -EINVAL;
        }

        if (size != paramSize) {
            return -EINVAL;
        }

        if (paramSize == sizeof(int16_t)) {
            value16 = *(int16_t *) pValue;
        } else if (paramSize == sizeof(int32_t)) {
            value32 = *(int32_t *) pValue;
        } else {
            pProperties = (t_reverb_settings *) pValue;
        }

        pPreset = &pReverb->m_sPreset.m_sPreset[pReverb->m_nNextRoom];

        switch (param) {
        case REVERB_PARAM_BYPASS:
            pReverb->m_bBypass = (uint16_t)value32;
            break;

        case REVERB_PARAM_PROPERTIES:
            value16 = pProperties->roomLevel;
            /* FALL THROUGH */

        case REVERB_PARAM_ROOM_LEVEL:
            // Convert millibels to linear 16 bit signed => m_nRoomLpfFwd
            if (value16 > 0)
                return -EINVAL;

            temp = Effects_MillibelsToLinear16(value16);

            pReverb->m_nRoomLpfFwd
                    = MULT_EG1_EG1(temp, (32767 - pReverb->m_nRoomLpfFbk));

            ALOGV("REVERB_PARAM_ROOM_LEVEL, gain %d, new m_nRoomLpfFwd %d, m_nRoomLpfFbk %d", temp, pReverb->m_nRoomLpfFwd, pReverb->m_nRoomLpfFbk);
            if (param == REVERB_PARAM_ROOM_LEVEL)
                break;
            value16 = pProperties->roomHFLevel;
            /* FALL THROUGH */

        case REVERB_PARAM_ROOM_HF_LEVEL:

            // Limit to 0 , -40dB range because of low pass implementation
            if (value16 > 0 || value16 < -4000)
                return -EINVAL;
            // Convert attenuation @ 5000H expressed in millibels to => m_nRoomLpfFbk
            // m_nRoomLpfFbk is -a1 where a1 is the solution of:
            // a1^2 + 2*(C-dG^2)/(1-dG^2)*a1 + 1 = 0 where:
            // - C is cos(2*pi*5000/Fs) (pReverb->m_nCosWT_5KHz)
            // - dG is G0/Gf (G0 is the linear gain at DC and Gf is the wanted gain at 5000Hz)

            // Save current DC gain m_nRoomLpfFwd / (32767 - m_nRoomLpfFbk) to keep it unchanged
            // while changing HF level
            temp2 = (pReverb->m_nRoomLpfFwd << 15) / (32767
                    - pReverb->m_nRoomLpfFbk);
            if (value16 == 0) {
                pReverb->m_nRoomLpfFbk = 0;
            } else {
                int32_t dG2, b, delta;

                // dG^2
                temp = Effects_MillibelsToLinear16(value16);
                ALOGV("REVERB_PARAM_ROOM_HF_LEVEL, HF gain %d", temp);
                temp = (1 << 30) / temp;
                ALOGV("REVERB_PARAM_ROOM_HF_LEVEL, 1/ HF gain %d", temp);
                dG2 = (int32_t) (((int64_t) temp * (int64_t) temp) >> 15);
                ALOGV("REVERB_PARAM_ROOM_HF_LEVEL, 1/ HF gain ^ 2 %d", dG2);
                // b = 2*(C-dG^2)/(1-dG^2)
                b = (int32_t) ((((int64_t) 1 << (15 + 1))
                        * ((int64_t) pReverb->m_nCosWT_5KHz - (int64_t) dG2))
                        / ((int64_t) 32767 - (int64_t) dG2));

                // delta = b^2 - 4
                delta = (int32_t) ((((int64_t) b * (int64_t) b) >> 15) - (1 << (15
                        + 2)));

                ALOGV_IF(delta > (1<<30), " delta overflow %d", delta);

                ALOGV("REVERB_PARAM_ROOM_HF_LEVEL, dG2 %d, b %d, delta %d, m_nCosWT_5KHz %d", dG2, b, delta, pReverb->m_nCosWT_5KHz);
                // m_nRoomLpfFbk = -a1 = - (- b + sqrt(delta)) / 2
                pReverb->m_nRoomLpfFbk = (b - Effects_Sqrt(delta) * 181) >> 1;
            }
            ALOGV("REVERB_PARAM_ROOM_HF_LEVEL, olg DC gain %d new m_nRoomLpfFbk %d, old m_nRoomLpfFwd %d",
                    temp2, pReverb->m_nRoomLpfFbk, pReverb->m_nRoomLpfFwd);

            pReverb->m_nRoomLpfFwd
                    = MULT_EG1_EG1(temp2, (32767 - pReverb->m_nRoomLpfFbk));
            ALOGV("REVERB_PARAM_ROOM_HF_LEVEL, new m_nRoomLpfFwd %d", pReverb->m_nRoomLpfFwd);

            if (param == REVERB_PARAM_ROOM_HF_LEVEL)
                break;
            value32 = pProperties->decayTime;
            /* FALL THROUGH */

        case REVERB_PARAM_DECAY_TIME:

            // Convert milliseconds to => m_nRvbLpfFwd (function of m_nRvbLpfFbk)
            // convert ms to samples
            value32 = (value32 * pReverb->m_nSamplingRate) / 1000;

            // calculate valid decay time range as a function of current reverb delay and
            // max feed back gain. Min value <=> -40dB in one pass, Max value <=> feedback gain = -1 dB
            // Calculate attenuation for each round in late reverb given a total attenuation of -6000 millibels.
            // g = -6000 d/DT , g gain in millibels, d reverb delay, DT decay time
            averageDelay = pReverb->m_nLateDelay - pReverb->m_nMaxExcursion;
            averageDelay += ((pReverb->m_sAp0.m_zApOut - pReverb->m_sAp0.m_zApIn)
                    + (pReverb->m_sAp1.m_zApOut - pReverb->m_sAp1.m_zApIn)) >> 1;

            temp = (-6000 * averageDelay) / value32;
            ALOGV("REVERB_PARAM_DECAY_TIME, delay smps %d, DT smps %d, gain mB %d",averageDelay, value32, temp);
            if (temp < -4000 || temp > -100)
                return -EINVAL;

            // calculate low pass gain by adding reverb input attenuation (pReverb->m_nLateGain) and substrating output
            // xfade and sum gain (max +9dB)
            temp -= Effects_Linear16ToMillibels(pReverb->m_nLateGain) + 900;
            temp = Effects_MillibelsToLinear16(temp);

            // DC gain (temp) = b0 / (1 + a1) = pReverb->m_nRvbLpfFwd / (32767 - pReverb->m_nRvbLpfFbk)
            pReverb->m_nRvbLpfFwd
                    = MULT_EG1_EG1(temp, (32767 - pReverb->m_nRvbLpfFbk));

            ALOGV("REVERB_PARAM_DECAY_TIME, gain %d, new m_nRvbLpfFwd %d, old m_nRvbLpfFbk %d, reverb gain %d", temp, pReverb->m_nRvbLpfFwd, pReverb->m_nRvbLpfFbk, Effects_Linear16ToMillibels(pReverb->m_nLateGain));

            if (param == REVERB_PARAM_DECAY_TIME)
                break;
            value16 = pProperties->decayHFRatio;
            /* FALL THROUGH */

        case REVERB_PARAM_DECAY_HF_RATIO:

            // We limit max value to 1000 because reverb filter is lowpass only
            if (value16 < 100 || value16 > 1000)
                return -EINVAL;
            // Convert per mille to => m_nLpfFwd, m_nLpfFbk

            // Save current DC gain m_nRoomLpfFwd / (32767 - m_nRoomLpfFbk) to keep it unchanged
            // while changing HF level
            temp2 = (pReverb->m_nRvbLpfFwd << 15) / (32767 - pReverb->m_nRvbLpfFbk);

            if (value16 == 1000) {
                pReverb->m_nRvbLpfFbk = 0;
            } else {
                int32_t dG2, b, delta;

                temp = Effects_Linear16ToMillibels(temp2);
                // G_5000Hz = G_DC * (1000/REVERB_PARAM_DECAY_HF_RATIO) in millibels

                value32 = ((int32_t) 1000 << 15) / (int32_t) value16;
                ALOGV("REVERB_PARAM_DECAY_HF_RATIO, DC gain %d, DC gain mB %d, 1000/R %d", temp2, temp, value32);

                temp = (int32_t) (((int64_t) temp * (int64_t) value32) >> 15);

                if (temp < -4000) {
                    ALOGV("REVERB_PARAM_DECAY_HF_RATIO HF gain overflow %d mB", temp);
                    temp = -4000;
                }

                temp = Effects_MillibelsToLinear16(temp);
                ALOGV("REVERB_PARAM_DECAY_HF_RATIO, HF gain %d", temp);
                // dG^2
                temp = (temp2 << 15) / temp;
                dG2 = (int32_t) (((int64_t) temp * (int64_t) temp) >> 15);

                // b = 2*(C-dG^2)/(1-dG^2)
                b = (int32_t) ((((int64_t) 1 << (15 + 1))
                        * ((int64_t) pReverb->m_nCosWT_5KHz - (int64_t) dG2))
                        / ((int64_t) 32767 - (int64_t) dG2));

                // delta = b^2 - 4
                delta = (int32_t) ((((int64_t) b * (int64_t) b) >> 15) - (1 << (15
                        + 2)));

                // m_nRoomLpfFbk = -a1 = - (- b + sqrt(delta)) / 2
                pReverb->m_nRvbLpfFbk = (b - Effects_Sqrt(delta) * 181) >> 1;

                ALOGV("REVERB_PARAM_DECAY_HF_RATIO, dG2 %d, b %d, delta %d", dG2, b, delta);

            }

            ALOGV("REVERB_PARAM_DECAY_HF_RATIO, gain %d, m_nRvbLpfFbk %d, m_nRvbLpfFwd %d", temp2, pReverb->m_nRvbLpfFbk, pReverb->m_nRvbLpfFwd);

            pReverb->m_nRvbLpfFwd
                    = MULT_EG1_EG1(temp2, (32767 - pReverb->m_nRvbLpfFbk));

            if (param == REVERB_PARAM_DECAY_HF_RATIO)
                break;
            value16 = pProperties->reflectionsLevel;
            /* FALL THROUGH */

        case REVERB_PARAM_REFLECTIONS_LEVEL:
            // We limit max value to 0 because gain is limited to 0dB
            if (value16 > 0 || value16 < -6000)
                return -EINVAL;

            // Convert millibels to linear 16 bit signed and recompute m_sEarlyL.m_nGain[i] and m_sEarlyR.m_nGain[i].
            value16 = Effects_MillibelsToLinear16(value16);
            for (i = 0; i < REVERB_MAX_NUM_REFLECTIONS; i++) {
                pReverb->m_sEarlyL.m_nGain[i]
                        = MULT_EG1_EG1(pPreset->m_sEarlyL.m_nGain[i],value16);
                pReverb->m_sEarlyR.m_nGain[i]
                        = MULT_EG1_EG1(pPreset->m_sEarlyR.m_nGain[i],value16);
            }
            pReverb->m_nEarlyGain = value16;
            ALOGV("REVERB_PARAM_REFLECTIONS_LEVEL, m_nEarlyGain %d", pReverb->m_nEarlyGain);

            if (param == REVERB_PARAM_REFLECTIONS_LEVEL)
                break;
            value32 = pProperties->reflectionsDelay;
            /* FALL THROUGH */

        case REVERB_PARAM_REFLECTIONS_DELAY:
            // We limit max value MAX_EARLY_TIME
            // convert ms to time units
            temp = (value32 * 65536) / 1000;
            if (temp < 0 || temp > MAX_EARLY_TIME)
                return -EINVAL;

            maxSamples = (int32_t) (MAX_EARLY_TIME * pReverb->m_nSamplingRate)
                    >> 16;
            temp = (temp * pReverb->m_nSamplingRate) >> 16;
            for (i = 0; i < REVERB_MAX_NUM_REFLECTIONS; i++) {
                temp2 = temp + (((int32_t) pPreset->m_sEarlyL.m_zDelay[i]
                        * pReverb->m_nSamplingRate) >> 16);
                if (temp2 > maxSamples)
                    temp2 = maxSamples;
                pReverb->m_sEarlyL.m_zDelay[i] = pReverb->m_nEarly0in + temp2;
                temp2 = temp + (((int32_t) pPreset->m_sEarlyR.m_zDelay[i]
                        * pReverb->m_nSamplingRate) >> 16);
                if (temp2 > maxSamples)
                    temp2 = maxSamples;
                pReverb->m_sEarlyR.m_zDelay[i] = pReverb->m_nEarly1in + temp2;
            }
            pReverb->m_nEarlyDelay = temp;

            ALOGV("REVERB_PARAM_REFLECTIONS_DELAY, m_nEarlyDelay smps %d max smp delay %d", pReverb->m_nEarlyDelay, maxSamples);

            // Convert milliseconds to sample count => m_nEarlyDelay
            if (param == REVERB_PARAM_REFLECTIONS_DELAY)
                break;
            value16 = pProperties->reverbLevel;
            /* FALL THROUGH */

        case REVERB_PARAM_REVERB_LEVEL:
            // We limit max value to 0 because gain is limited to 0dB
            if (value16 > 0 || value16 < -6000)
                return -EINVAL;
            // Convert millibels to linear 16 bits (gange 0 - 8191) => m_nLateGain.
            pReverb->m_nLateGain = Effects_MillibelsToLinear16(value16) >> 2;

            ALOGV("REVERB_PARAM_REVERB_LEVEL, m_nLateGain %d", pReverb->m_nLateGain);

            if (param == REVERB_PARAM_REVERB_LEVEL)
                break;
            value32 = pProperties->reverbDelay;
            /* FALL THROUGH */

        case REVERB_PARAM_REVERB_DELAY:
            // We limit max value to MAX_DELAY_TIME
            // convert ms to time units
            temp = (value32 * 65536) / 1000;
            if (temp < 0 || temp > MAX_DELAY_TIME)
                return -EINVAL;

            maxSamples = (int32_t) (MAX_DELAY_TIME * pReverb->m_nSamplingRate)
                    >> 16;
            temp = (temp * pReverb->m_nSamplingRate) >> 16;
            if ((temp + pReverb->m_nMaxExcursion) > maxSamples) {
                temp = maxSamples - pReverb->m_nMaxExcursion;
            }
            if (temp < pReverb->m_nMaxExcursion) {
                temp = pReverb->m_nMaxExcursion;
            }

            temp -= pReverb->m_nLateDelay;
            pReverb->m_nDelay0Out += temp;
            pReverb->m_nDelay1Out += temp;
            pReverb->m_nLateDelay += temp;

            ALOGV("REVERB_PARAM_REVERB_DELAY, m_nLateDelay smps %d max smp delay %d", pReverb->m_nLateDelay, maxSamples);

            // Convert milliseconds to sample count => m_nDelay1Out + m_nMaxExcursion
            if (param == REVERB_PARAM_REVERB_DELAY)
                break;

            value16 = pProperties->diffusion;
            /* FALL THROUGH */

        case REVERB_PARAM_DIFFUSION:
            if (value16 < 0 || value16 > 1000)
                return -EINVAL;

            // Convert per mille to m_sAp0.m_nApGain, m_sAp1.m_nApGain
            pReverb->m_sAp0.m_nApGain = AP0_GAIN_BASE + ((int32_t) value16
                    * AP0_GAIN_RANGE) / 1000;
            pReverb->m_sAp1.m_nApGain = AP1_GAIN_BASE + ((int32_t) value16
                    * AP1_GAIN_RANGE) / 1000;

            ALOGV("REVERB_PARAM_DIFFUSION, m_sAp0.m_nApGain %d m_sAp1.m_nApGain %d", pReverb->m_sAp0.m_nApGain, pReverb->m_sAp1.m_nApGain);

            if (param == REVERB_PARAM_DIFFUSION)
                break;

            value16 = pProperties->density;
            /* FALL THROUGH */

        case REVERB_PARAM_DENSITY:
            if (value16 < 0 || value16 > 1000)
                return -EINVAL;

            // Convert per mille to m_sAp0.m_zApOut, m_sAp1.m_zApOut
            maxSamples = (int32_t) (MAX_AP_TIME * pReverb->m_nSamplingRate) >> 16;

            temp = AP0_TIME_BASE + ((int32_t) value16 * AP0_TIME_RANGE) / 1000;
            /*lint -e{702} shift for performance */
            temp = (temp * pReverb->m_nSamplingRate) >> 16;
            if (temp > maxSamples)
                temp = maxSamples;
            pReverb->m_sAp0.m_zApOut = (uint16_t) (pReverb->m_sAp0.m_zApIn + temp);

            ALOGV("REVERB_PARAM_DENSITY, Ap0 delay smps %d", temp);

            temp = AP1_TIME_BASE + ((int32_t) value16 * AP1_TIME_RANGE) / 1000;
            /*lint -e{702} shift for performance */
            temp = (temp * pReverb->m_nSamplingRate) >> 16;
            if (temp > maxSamples)
                temp = maxSamples;
            pReverb->m_sAp1.m_zApOut = (uint16_t) (pReverb->m_sAp1.m_zApIn + temp);

            ALOGV("Ap1 delay smps %d", temp);

            break;

        default:
            break;
        }
    }

    return 0;
} /* end Reverb_setParameter */

/*----------------------------------------------------------------------------
 * ReverbUpdateXfade
 *----------------------------------------------------------------------------
 * Purpose:
 * Update the xfade parameters as required
 *
 * Inputs:
 * nNumSamplesToAdd - number of samples to write to buffer
 *
 * Outputs:
 *
 *
 * Side Effects:
 * - xfade parameters will be changed
 *
 *----------------------------------------------------------------------------
 */
static int ReverbUpdateXfade(reverb_object_t *pReverb, int nNumSamplesToAdd) {
    uint16_t nOffset;
    int16_t tempCos;
    int16_t tempSin;

    if (pReverb->m_nXfadeCounter >= pReverb->m_nXfadeInterval) {
        /* update interval has elapsed, so reset counter */
        pReverb->m_nXfadeCounter = 0;

        // Pin the sin,cos values to min / max values to ensure that the
        // modulated taps' coefs are zero (thus no clicks)
        if (pReverb->m_nPhaseIncrement > 0) {
            // if phase increment > 0, then sin -> 1, cos -> 0
            pReverb->m_nSin = 32767;
            pReverb->m_nCos = 0;

            // reset the phase to match the sin, cos values
            pReverb->m_nPhase = 32767;

            // modulate the cross taps because their tap coefs are zero
            nOffset = ReverbCalculateNoise(pReverb);

            pReverb->m_zD1Cross = pReverb->m_nDelay1Out
                    - pReverb->m_nMaxExcursion + nOffset;

            nOffset = ReverbCalculateNoise(pReverb);

            pReverb->m_zD0Cross = pReverb->m_nDelay0Out
                    - pReverb->m_nMaxExcursion - nOffset;
        } else {
            // if phase increment < 0, then sin -> 0, cos -> 1
            pReverb->m_nSin = 0;
            pReverb->m_nCos = 32767;

            // reset the phase to match the sin, cos values
            pReverb->m_nPhase = -32768;

            // modulate the self taps because their tap coefs are zero
            nOffset = ReverbCalculateNoise(pReverb);

            pReverb->m_zD0Self = pReverb->m_nDelay0Out
                    - pReverb->m_nMaxExcursion - nOffset;

            nOffset = ReverbCalculateNoise(pReverb);

            pReverb->m_zD1Self = pReverb->m_nDelay1Out
                    - pReverb->m_nMaxExcursion + nOffset;

        } // end if-else (pReverb->m_nPhaseIncrement > 0)

        // Reverse the direction of the sin,cos so that the
        // tap whose coef was previously increasing now decreases
        // and vice versa
        pReverb->m_nPhaseIncrement = -pReverb->m_nPhaseIncrement;

    } // end if counter >= update interval

    //compute what phase will be next time
    pReverb->m_nPhase += pReverb->m_nPhaseIncrement;

    //calculate what the new sin and cos need to reach by the next update
    ReverbCalculateSinCos(pReverb->m_nPhase, &tempSin, &tempCos);

    //calculate the per-sample increment required to get there by the next update
    /*lint -e{702} shift for performance */
    pReverb->m_nSinIncrement = (tempSin - pReverb->m_nSin)
            >> pReverb->m_nUpdatePeriodInBits;

    /*lint -e{702} shift for performance */
    pReverb->m_nCosIncrement = (tempCos - pReverb->m_nCos)
            >> pReverb->m_nUpdatePeriodInBits;

    /* increment update counter */
    pReverb->m_nXfadeCounter += (uint16_t) nNumSamplesToAdd;

    return 0;

} /* end ReverbUpdateXfade */

/*----------------------------------------------------------------------------
 * ReverbCalculateNoise
 *----------------------------------------------------------------------------
 * Purpose:
 * Calculate a noise sample and limit its value
 *
 * Inputs:
 * nMaxExcursion - noise value is limited to this value
 * pnNoise - return new noise sample in this (not limited)
 *
 * Outputs:
 * new limited noise value
 *
 * Side Effects:
 * - *pnNoise noise value is updated
 *
 *----------------------------------------------------------------------------
 */
static uint16_t ReverbCalculateNoise(reverb_object_t *pReverb) {
    int16_t nNoise = pReverb->m_nNoise;

    // calculate new noise value
    if (pReverb->m_bUseNoise) {
        nNoise = (int16_t) (nNoise * 5 + 1);
    } else {
        nNoise = 0;
    }

    pReverb->m_nNoise = nNoise;
    // return the limited noise value
    return (pReverb->m_nMaxExcursion & nNoise);

} /* end ReverbCalculateNoise */

/*----------------------------------------------------------------------------
 * ReverbCalculateSinCos
 *----------------------------------------------------------------------------
 * Purpose:
 * Calculate a new sin and cosine value based on the given phase
 *
 * Inputs:
 * nPhase   - phase angle
 * pnSin    - input old value, output new value
 * pnCos    - input old value, output new value
 *
 * Outputs:
 *
 * Side Effects:
 * - *pnSin, *pnCos are updated
 *
 *----------------------------------------------------------------------------
 */
static int ReverbCalculateSinCos(int16_t nPhase, int16_t *pnSin, int16_t *pnCos) {
    int32_t nTemp;
    int32_t nNetAngle;

    //  -1 <=  nPhase  < 1
    // However, for the calculation, we need a value
    // that ranges from -1/2 to +1/2, so divide the phase by 2
    /*lint -e{702} shift for performance */
    nNetAngle = nPhase >> 1;

    /*
     Implement the following
     sin(x) = (2-4*c)*x^2 + c + x
     cos(x) = (2-4*c)*x^2 + c - x

     where  c = 1/sqrt(2)
     using the a0 + x*(a1 + x*a2) approach
     */

    /* limit the input "angle" to be between -0.5 and +0.5 */
    if (nNetAngle > EG1_HALF) {
        nNetAngle = EG1_HALF;
    } else if (nNetAngle < EG1_MINUS_HALF) {
        nNetAngle = EG1_MINUS_HALF;
    }

    /* calculate sin */
    nTemp = EG1_ONE + MULT_EG1_EG1(REVERB_PAN_G2, nNetAngle);
    nTemp = REVERB_PAN_G0 + MULT_EG1_EG1(nTemp, nNetAngle);
    *pnSin = (int16_t) SATURATE_EG1(nTemp);

    /* calculate cos */
    nTemp = -EG1_ONE + MULT_EG1_EG1(REVERB_PAN_G2, nNetAngle);
    nTemp = REVERB_PAN_G0 + MULT_EG1_EG1(nTemp, nNetAngle);
    *pnCos = (int16_t) SATURATE_EG1(nTemp);

    return 0;
} /* end ReverbCalculateSinCos */

/*----------------------------------------------------------------------------
 * Reverb
 *----------------------------------------------------------------------------
 * Purpose:
 * apply reverb to the given signal
 *
 * Inputs:
 * nNu
 * pnSin    - input old value, output new value
 * pnCos    - input old value, output new value
 *
 * Outputs:
 * number of samples actually reverberated
 *
 * Side Effects:
 *
 *----------------------------------------------------------------------------
 */
static int Reverb(reverb_object_t *pReverb, int nNumSamplesToAdd,
        short *pOutputBuffer, short *pInputBuffer) {
    int32_t i;
    int32_t nDelayOut0;
    int32_t nDelayOut1;
    uint16_t nBase;

    uint32_t nAddr;
    int32_t nTemp1;
    int32_t nTemp2;
    int32_t nApIn;
    int32_t nApOut;

    int32_t j;
    int32_t nEarlyOut;

    int32_t tempValue;

    // get the base address
    nBase = pReverb->m_nBaseIndex;

    for (i = 0; i < nNumSamplesToAdd; i++) {
        // ********** Left Allpass - start
        nApIn = *pInputBuffer;
        if (!pReverb->m_Aux) {
            pInputBuffer++;
        }
        // store to early delay line
        nAddr = CIRCULAR(nBase, pReverb->m_nEarly0in, pReverb->m_nBufferMask);
        pReverb->m_nDelayLine[nAddr] = (short) nApIn;

        // left input = (left dry * m_nLateGain) + right feedback from previous period

        nApIn = SATURATE(nApIn + pReverb->m_nRevFbkR);
        nApIn = MULT_EG1_EG1(nApIn, pReverb->m_nLateGain);

        // fetch allpass delay line out
        //nAddr = CIRCULAR(nBase, psAp0->m_zApOut, pReverb->m_nBufferMask);
        nAddr
                = CIRCULAR(nBase, pReverb->m_sAp0.m_zApOut, pReverb->m_nBufferMask);
        nDelayOut0 = pReverb->m_nDelayLine[nAddr];

        // calculate allpass feedforward; subtract the feedforward result
        nTemp1 = MULT_EG1_EG1(nApIn, pReverb->m_sAp0.m_nApGain);
        nApOut = SATURATE(nDelayOut0 - nTemp1); // allpass output

        // calculate allpass feedback; add the feedback result
        nTemp1 = MULT_EG1_EG1(nApOut, pReverb->m_sAp0.m_nApGain);
        nTemp1 = SATURATE(nApIn + nTemp1);

        // inject into allpass delay
        nAddr
                = CIRCULAR(nBase, pReverb->m_sAp0.m_zApIn, pReverb->m_nBufferMask);
        pReverb->m_nDelayLine[nAddr] = (short) nTemp1;

        // inject allpass output into delay line
        nAddr = CIRCULAR(nBase, pReverb->m_zD0In, pReverb->m_nBufferMask);
        pReverb->m_nDelayLine[nAddr] = (short) nApOut;

        // ********** Left Allpass - end

        // ********** Right Allpass - start
        nApIn = (*pInputBuffer++);
        // store to early delay line
        nAddr = CIRCULAR(nBase, pReverb->m_nEarly1in, pReverb->m_nBufferMask);
        pReverb->m_nDelayLine[nAddr] = (short) nApIn;

        // right input = (right dry * m_nLateGain) + left feedback from previous period
        /*lint -e{702} use shift for performance */
        nApIn = SATURATE(nApIn + pReverb->m_nRevFbkL);
        nApIn = MULT_EG1_EG1(nApIn, pReverb->m_nLateGain);

        // fetch allpass delay line out
        nAddr
                = CIRCULAR(nBase, pReverb->m_sAp1.m_zApOut, pReverb->m_nBufferMask);
        nDelayOut1 = pReverb->m_nDelayLine[nAddr];

        // calculate allpass feedforward; subtract the feedforward result
        nTemp1 = MULT_EG1_EG1(nApIn, pReverb->m_sAp1.m_nApGain);
        nApOut = SATURATE(nDelayOut1 - nTemp1); // allpass output

        // calculate allpass feedback; add the feedback result
        nTemp1 = MULT_EG1_EG1(nApOut, pReverb->m_sAp1.m_nApGain);
        nTemp1 = SATURATE(nApIn + nTemp1);

        // inject into allpass delay
        nAddr
                = CIRCULAR(nBase, pReverb->m_sAp1.m_zApIn, pReverb->m_nBufferMask);
        pReverb->m_nDelayLine[nAddr] = (short) nTemp1;

        // inject allpass output into delay line
        nAddr = CIRCULAR(nBase, pReverb->m_zD1In, pReverb->m_nBufferMask);
        pReverb->m_nDelayLine[nAddr] = (short) nApOut;

        // ********** Right Allpass - end

        // ********** D0 output - start
        // fetch delay line self out
        nAddr = CIRCULAR(nBase, pReverb->m_zD0Self, pReverb->m_nBufferMask);
        nDelayOut0 = pReverb->m_nDelayLine[nAddr];

        // calculate delay line self out
        nTemp1 = MULT_EG1_EG1(nDelayOut0, pReverb->m_nSin);

        // fetch delay line cross out
        nAddr = CIRCULAR(nBase, pReverb->m_zD1Cross, pReverb->m_nBufferMask);
        nDelayOut0 = pReverb->m_nDelayLine[nAddr];

        // calculate delay line self out
        nTemp2 = MULT_EG1_EG1(nDelayOut0, pReverb->m_nCos);

        // calculate unfiltered delay out
        nDelayOut0 = SATURATE(nTemp1 + nTemp2);

        // ********** D0 output - end

        // ********** D1 output - start
        // fetch delay line self out
        nAddr = CIRCULAR(nBase, pReverb->m_zD1Self, pReverb->m_nBufferMask);
        nDelayOut1 = pReverb->m_nDelayLine[nAddr];

        // calculate delay line self out
        nTemp1 = MULT_EG1_EG1(nDelayOut1, pReverb->m_nSin);

        // fetch delay line cross out
        nAddr = CIRCULAR(nBase, pReverb->m_zD0Cross, pReverb->m_nBufferMask);
        nDelayOut1 = pReverb->m_nDelayLine[nAddr];

        // calculate delay line self out
        nTemp2 = MULT_EG1_EG1(nDelayOut1, pReverb->m_nCos);

        // calculate unfiltered delay out
        nDelayOut1 = SATURATE(nTemp1 + nTemp2);

        // ********** D1 output - end

        // ********** mixer and feedback - start
        // sum is fedback to right input (R + L)
        nDelayOut0 = (short) SATURATE(nDelayOut0 + nDelayOut1);

        // difference is feedback to left input (R - L)
        /*lint -e{685} lint complains that it can't saturate negative */
        nDelayOut1 = (short) SATURATE(nDelayOut1 - nDelayOut0);

        // ********** mixer and feedback - end

        // calculate lowpass filter (mixer scale factor included in LPF feedforward)
        nTemp1 = MULT_EG1_EG1(nDelayOut0, pReverb->m_nRvbLpfFwd);

        nTemp2 = MULT_EG1_EG1(pReverb->m_nRevFbkL, pReverb->m_nRvbLpfFbk);

        // calculate filtered delay out and simultaneously update LPF state variable
        // filtered delay output is stored in m_nRevFbkL
        pReverb->m_nRevFbkL = (short) SATURATE(nTemp1 + nTemp2);

        // calculate lowpass filter (mixer scale factor included in LPF feedforward)
        nTemp1 = MULT_EG1_EG1(nDelayOut1, pReverb->m_nRvbLpfFwd);

        nTemp2 = MULT_EG1_EG1(pReverb->m_nRevFbkR, pReverb->m_nRvbLpfFbk);

        // calculate filtered delay out and simultaneously update LPF state variable
        // filtered delay output is stored in m_nRevFbkR
        pReverb->m_nRevFbkR = (short) SATURATE(nTemp1 + nTemp2);

        // ********** start early reflection generator, left
        //psEarly = &(pReverb->m_sEarlyL);


        for (j = 0; j < REVERB_MAX_NUM_REFLECTIONS; j++) {
            // fetch delay line out
            //nAddr = CIRCULAR(nBase, psEarly->m_zDelay[j], pReverb->m_nBufferMask);
            nAddr
                    = CIRCULAR(nBase, pReverb->m_sEarlyL.m_zDelay[j], pReverb->m_nBufferMask);

            nTemp1 = pReverb->m_nDelayLine[nAddr];

            // calculate reflection
            //nTemp1 = MULT_EG1_EG1(nDelayOut0, psEarly->m_nGain[j]);
            nTemp1 = MULT_EG1_EG1(nTemp1, pReverb->m_sEarlyL.m_nGain[j]);

            nDelayOut0 = SATURATE(nDelayOut0 + nTemp1);

        } // end for (j=0; j < REVERB_MAX_NUM_REFLECTIONS; j++)

        // apply lowpass to early reflections and reverb output
        //nTemp1 = MULT_EG1_EG1(nEarlyOut, psEarly->m_nRvbLpfFwd);
        nTemp1 = MULT_EG1_EG1(nDelayOut0, pReverb->m_nRoomLpfFwd);

        //nTemp2 = MULT_EG1_EG1(psEarly->m_zLpf, psEarly->m_nLpfFbk);
        nTemp2 = MULT_EG1_EG1(pReverb->m_zOutLpfL, pReverb->m_nRoomLpfFbk);

        // calculate filtered out and simultaneously update LPF state variable
        // filtered output is stored in m_zOutLpfL
        pReverb->m_zOutLpfL = (short) SATURATE(nTemp1 + nTemp2);

        //sum with output buffer
        tempValue = *pOutputBuffer;
        *pOutputBuffer++ = (short) SATURATE(tempValue+pReverb->m_zOutLpfL);

        // ********** end early reflection generator, left

        // ********** start early reflection generator, right
        //psEarly = &(pReverb->m_sEarlyR);

        for (j = 0; j < REVERB_MAX_NUM_REFLECTIONS; j++) {
            // fetch delay line out
            nAddr
                    = CIRCULAR(nBase, pReverb->m_sEarlyR.m_zDelay[j], pReverb->m_nBufferMask);
            nTemp1 = pReverb->m_nDelayLine[nAddr];

            // calculate reflection
            nTemp1 = MULT_EG1_EG1(nTemp1, pReverb->m_sEarlyR.m_nGain[j]);

            nDelayOut1 = SATURATE(nDelayOut1 + nTemp1);

        } // end for (j=0; j < REVERB_MAX_NUM_REFLECTIONS; j++)

        // apply lowpass to early reflections
        nTemp1 = MULT_EG1_EG1(nDelayOut1, pReverb->m_nRoomLpfFwd);

        nTemp2 = MULT_EG1_EG1(pReverb->m_zOutLpfR, pReverb->m_nRoomLpfFbk);

        // calculate filtered out and simultaneously update LPF state variable
        // filtered output is stored in m_zOutLpfR
        pReverb->m_zOutLpfR = (short) SATURATE(nTemp1 + nTemp2);

        //sum with output buffer
        tempValue = *pOutputBuffer;
        *pOutputBuffer++ = (short) SATURATE(tempValue + pReverb->m_zOutLpfR);

        // ********** end early reflection generator, right

        // decrement base addr for next sample period
        nBase--;

        pReverb->m_nSin += pReverb->m_nSinIncrement;
        pReverb->m_nCos += pReverb->m_nCosIncrement;

    } // end for (i=0; i < nNumSamplesToAdd; i++)

    // store the most up to date version
    pReverb->m_nBaseIndex = nBase;

    return 0;
} /* end Reverb */

/*----------------------------------------------------------------------------
 * ReverbUpdateRoom
 *----------------------------------------------------------------------------
 * Purpose:
 * Update the room's preset parameters as required
 *
 * Inputs:
 *
 * Outputs:
 *
 *
 * Side Effects:
 * - reverb paramters (fbk, fwd, etc) will be changed
 * - m_nCurrentRoom := m_nNextRoom
 *----------------------------------------------------------------------------
 */
static int ReverbUpdateRoom(reverb_object_t *pReverb, bool fullUpdate) {
    int temp;
    int i;
    int maxSamples;
    int earlyDelay;
    int earlyGain;

    reverb_preset_t *pPreset =
            &pReverb->m_sPreset.m_sPreset[pReverb->m_nNextRoom];

    if (fullUpdate) {
        pReverb->m_nRvbLpfFwd = pPreset->m_nRvbLpfFwd;
        pReverb->m_nRvbLpfFbk = pPreset->m_nRvbLpfFbk;

        pReverb->m_nEarlyGain = pPreset->m_nEarlyGain;
        //stored as time based, convert to sample based
        pReverb->m_nLateGain = pPreset->m_nLateGain;
        pReverb->m_nRoomLpfFbk = pPreset->m_nRoomLpfFbk;
        pReverb->m_nRoomLpfFwd = pPreset->m_nRoomLpfFwd;

        // set the early reflections gains
        earlyGain = pPreset->m_nEarlyGain;
        for (i = 0; i < REVERB_MAX_NUM_REFLECTIONS; i++) {
            pReverb->m_sEarlyL.m_nGain[i]
                    = MULT_EG1_EG1(pPreset->m_sEarlyL.m_nGain[i],earlyGain);
            pReverb->m_sEarlyR.m_nGain[i]
                    = MULT_EG1_EG1(pPreset->m_sEarlyR.m_nGain[i],earlyGain);
        }

        pReverb->m_nMaxExcursion = pPreset->m_nMaxExcursion;

        pReverb->m_sAp0.m_nApGain = pPreset->m_nAp0_ApGain;
        pReverb->m_sAp1.m_nApGain = pPreset->m_nAp1_ApGain;

        // set the early reflections delay
        earlyDelay = ((int) pPreset->m_nEarlyDelay * pReverb->m_nSamplingRate)
                >> 16;
        pReverb->m_nEarlyDelay = earlyDelay;
        maxSamples = (int32_t) (MAX_EARLY_TIME * pReverb->m_nSamplingRate)
                >> 16;
        for (i = 0; i < REVERB_MAX_NUM_REFLECTIONS; i++) {
            //stored as time based, convert to sample based
            temp = earlyDelay + (((int) pPreset->m_sEarlyL.m_zDelay[i]
                    * pReverb->m_nSamplingRate) >> 16);
            if (temp > maxSamples)
                temp = maxSamples;
            pReverb->m_sEarlyL.m_zDelay[i] = pReverb->m_nEarly0in + temp;
            //stored as time based, convert to sample based
            temp = earlyDelay + (((int) pPreset->m_sEarlyR.m_zDelay[i]
                    * pReverb->m_nSamplingRate) >> 16);
            if (temp > maxSamples)
                temp = maxSamples;
            pReverb->m_sEarlyR.m_zDelay[i] = pReverb->m_nEarly1in + temp;
        }

        maxSamples = (int32_t) (MAX_DELAY_TIME * pReverb->m_nSamplingRate)
                >> 16;
        //stored as time based, convert to sample based
        /*lint -e{702} shift for performance */
        temp = (pPreset->m_nLateDelay * pReverb->m_nSamplingRate) >> 16;
        if ((temp + pReverb->m_nMaxExcursion) > maxSamples) {
            temp = maxSamples - pReverb->m_nMaxExcursion;
        }
        temp -= pReverb->m_nLateDelay;
        pReverb->m_nDelay0Out += temp;
        pReverb->m_nDelay1Out += temp;
        pReverb->m_nLateDelay += temp;

        maxSamples = (int32_t) (MAX_AP_TIME * pReverb->m_nSamplingRate) >> 16;
        //stored as time based, convert to absolute sample value
        temp = pPreset->m_nAp0_ApOut;
        /*lint -e{702} shift for performance */
        temp = (temp * pReverb->m_nSamplingRate) >> 16;
        if (temp > maxSamples)
            temp = maxSamples;
        pReverb->m_sAp0.m_zApOut = (uint16_t) (pReverb->m_sAp0.m_zApIn + temp);

        //stored as time based, convert to absolute sample value
        temp = pPreset->m_nAp1_ApOut;
        /*lint -e{702} shift for performance */
        temp = (temp * pReverb->m_nSamplingRate) >> 16;
        if (temp > maxSamples)
            temp = maxSamples;
        pReverb->m_sAp1.m_zApOut = (uint16_t) (pReverb->m_sAp1.m_zApIn + temp);
        //gpsReverbObject->m_sAp1.m_zApOut = pPreset->m_nAp1_ApOut;
    }

    //stored as time based, convert to sample based
    temp = pPreset->m_nXfadeInterval;
    /*lint -e{702} shift for performance */
    temp = (temp * pReverb->m_nSamplingRate) >> 16;
    pReverb->m_nXfadeInterval = (uint16_t) temp;
    //gsReverbObject.m_nXfadeInterval = pPreset->m_nXfadeInterval;
    pReverb->m_nXfadeCounter = pReverb->m_nXfadeInterval + 1; // force update on first iteration

    pReverb->m_nCurrentRoom = pReverb->m_nNextRoom;

    return 0;

} /* end ReverbUpdateRoom */

/*----------------------------------------------------------------------------
 * ReverbReadInPresets()
 *----------------------------------------------------------------------------
 * Purpose: sets global reverb preset bank to defaults
 *
 * Inputs:
 *
 * Outputs:
 *
 *----------------------------------------------------------------------------
 */
static int ReverbReadInPresets(reverb_object_t *pReverb) {

    int preset;

    // this is for test only. OpenSL ES presets are mapped to 4 presets.
    // REVERB_PRESET_NONE is mapped to bypass
    for (preset = 0; preset < REVERB_NUM_PRESETS; preset++) {
        reverb_preset_t *pPreset = &pReverb->m_sPreset.m_sPreset[preset];
        switch (preset + 1) {
        case REVERB_PRESET_PLATE:
        case REVERB_PRESET_SMALLROOM:
            pPreset->m_nRvbLpfFbk = 5077;
            pPreset->m_nRvbLpfFwd = 11076;
            pPreset->m_nEarlyGain = 27690;
            pPreset->m_nEarlyDelay = 1311;
            pPreset->m_nLateGain = 8191;
            pPreset->m_nLateDelay = 3932;
            pPreset->m_nRoomLpfFbk = 3692;
            pPreset->m_nRoomLpfFwd = 20474;
            pPreset->m_sEarlyL.m_zDelay[0] = 1376;
            pPreset->m_sEarlyL.m_nGain[0] = 22152;
            pPreset->m_sEarlyL.m_zDelay[1] = 1462;
            pPreset->m_sEarlyL.m_nGain[1] = 17537;
            pPreset->m_sEarlyL.m_zDelay[2] = 0;
            pPreset->m_sEarlyL.m_nGain[2] = 14768;
            pPreset->m_sEarlyL.m_zDelay[3] = 1835;
            pPreset->m_sEarlyL.m_nGain[3] = 14307;
            pPreset->m_sEarlyL.m_zDelay[4] = 0;
            pPreset->m_sEarlyL.m_nGain[4] = 13384;
            pPreset->m_sEarlyR.m_zDelay[0] = 721;
            pPreset->m_sEarlyR.m_nGain[0] = 20306;
            pPreset->m_sEarlyR.m_zDelay[1] = 2621;
            pPreset->m_sEarlyR.m_nGain[1] = 17537;
            pPreset->m_sEarlyR.m_zDelay[2] = 0;
            pPreset->m_sEarlyR.m_nGain[2] = 14768;
            pPreset->m_sEarlyR.m_zDelay[3] = 0;
            pPreset->m_sEarlyR.m_nGain[3] = 16153;
            pPreset->m_sEarlyR.m_zDelay[4] = 0;
            pPreset->m_sEarlyR.m_nGain[4] = 13384;
            pPreset->m_nMaxExcursion = 127;
            pPreset->m_nXfadeInterval = 6470; //6483;
            pPreset->m_nAp0_ApGain = 14768;
            pPreset->m_nAp0_ApOut = 792;
            pPreset->m_nAp1_ApGain = 14777;
            pPreset->m_nAp1_ApOut = 1191;
            pPreset->m_rfu4 = 0;
            pPreset->m_rfu5 = 0;
            pPreset->m_rfu6 = 0;
            pPreset->m_rfu7 = 0;
            pPreset->m_rfu8 = 0;
            pPreset->m_rfu9 = 0;
            pPreset->m_rfu10 = 0;
            break;
        case REVERB_PRESET_MEDIUMROOM:
        case REVERB_PRESET_LARGEROOM:
            pPreset->m_nRvbLpfFbk = 5077;
            pPreset->m_nRvbLpfFwd = 12922;
            pPreset->m_nEarlyGain = 27690;
            pPreset->m_nEarlyDelay = 1311;
            pPreset->m_nLateGain = 8191;
            pPreset->m_nLateDelay = 3932;
            pPreset->m_nRoomLpfFbk = 3692;
            pPreset->m_nRoomLpfFwd = 21703;
            pPreset->m_sEarlyL.m_zDelay[0] = 1376;
            pPreset->m_sEarlyL.m_nGain[0] = 22152;
            pPreset->m_sEarlyL.m_zDelay[1] = 1462;
            pPreset->m_sEarlyL.m_nGain[1] = 17537;
            pPreset->m_sEarlyL.m_zDelay[2] = 0;
            pPreset->m_sEarlyL.m_nGain[2] = 14768;
            pPreset->m_sEarlyL.m_zDelay[3] = 1835;
            pPreset->m_sEarlyL.m_nGain[3] = 14307;
            pPreset->m_sEarlyL.m_zDelay[4] = 0;
            pPreset->m_sEarlyL.m_nGain[4] = 13384;
            pPreset->m_sEarlyR.m_zDelay[0] = 721;
            pPreset->m_sEarlyR.m_nGain[0] = 20306;
            pPreset->m_sEarlyR.m_zDelay[1] = 2621;
            pPreset->m_sEarlyR.m_nGain[1] = 17537;
            pPreset->m_sEarlyR.m_zDelay[2] = 0;
            pPreset->m_sEarlyR.m_nGain[2] = 14768;
            pPreset->m_sEarlyR.m_zDelay[3] = 0;
            pPreset->m_sEarlyR.m_nGain[3] = 16153;
            pPreset->m_sEarlyR.m_zDelay[4] = 0;
            pPreset->m_sEarlyR.m_nGain[4] = 13384;
            pPreset->m_nMaxExcursion = 127;
            pPreset->m_nXfadeInterval = 6449;
            pPreset->m_nAp0_ApGain = 15691;
            pPreset->m_nAp0_ApOut = 774;
            pPreset->m_nAp1_ApGain = 16317;
            pPreset->m_nAp1_ApOut = 1155;
            pPreset->m_rfu4 = 0;
            pPreset->m_rfu5 = 0;
            pPreset->m_rfu6 = 0;
            pPreset->m_rfu7 = 0;
            pPreset->m_rfu8 = 0;
            pPreset->m_rfu9 = 0;
            pPreset->m_rfu10 = 0;
            break;
        case REVERB_PRESET_MEDIUMHALL:
            pPreset->m_nRvbLpfFbk = 6461;
            pPreset->m_nRvbLpfFwd = 14307;
            pPreset->m_nEarlyGain = 27690;
            pPreset->m_nEarlyDelay = 1311;
            pPreset->m_nLateGain = 8191;
            pPreset->m_nLateDelay = 3932;
            pPreset->m_nRoomLpfFbk = 3692;
            pPreset->m_nRoomLpfFwd = 24569;
            pPreset->m_sEarlyL.m_zDelay[0] = 1376;
            pPreset->m_sEarlyL.m_nGain[0] = 22152;
            pPreset->m_sEarlyL.m_zDelay[1] = 1462;
            pPreset->m_sEarlyL.m_nGain[1] = 17537;
            pPreset->m_sEarlyL.m_zDelay[2] = 0;
            pPreset->m_sEarlyL.m_nGain[2] = 14768;
            pPreset->m_sEarlyL.m_zDelay[3] = 1835;
            pPreset->m_sEarlyL.m_nGain[3] = 14307;
            pPreset->m_sEarlyL.m_zDelay[4] = 0;
            pPreset->m_sEarlyL.m_nGain[4] = 13384;
            pPreset->m_sEarlyR.m_zDelay[0] = 721;
            pPreset->m_sEarlyR.m_nGain[0] = 20306;
            pPreset->m_sEarlyR.m_zDelay[1] = 2621;
            pPreset->m_sEarlyR.m_nGain[1] = 17537;
            pPreset->m_sEarlyR.m_zDelay[2] = 0;
            pPreset->m_sEarlyR.m_nGain[2] = 14768;
            pPreset->m_sEarlyR.m_zDelay[3] = 0;
            pPreset->m_sEarlyR.m_nGain[3] = 16153;
            pPreset->m_sEarlyR.m_zDelay[4] = 0;
            pPreset->m_sEarlyR.m_nGain[4] = 13384;
            pPreset->m_nMaxExcursion = 127;
            pPreset->m_nXfadeInterval = 6391;
            pPreset->m_nAp0_ApGain = 15230;
            pPreset->m_nAp0_ApOut = 708;
            pPreset->m_nAp1_ApGain = 15547;
            pPreset->m_nAp1_ApOut = 1023;
            pPreset->m_rfu4 = 0;
            pPreset->m_rfu5 = 0;
            pPreset->m_rfu6 = 0;
            pPreset->m_rfu7 = 0;
            pPreset->m_rfu8 = 0;
            pPreset->m_rfu9 = 0;
            pPreset->m_rfu10 = 0;
            break;
        case REVERB_PRESET_LARGEHALL:
            pPreset->m_nRvbLpfFbk = 8307;
            pPreset->m_nRvbLpfFwd = 14768;
            pPreset->m_nEarlyGain = 27690;
            pPreset->m_nEarlyDelay = 1311;
            pPreset->m_nLateGain = 8191;
            pPreset->m_nLateDelay = 3932;
            pPreset->m_nRoomLpfFbk = 3692;
            pPreset->m_nRoomLpfFwd = 24569;
            pPreset->m_sEarlyL.m_zDelay[0] = 1376;
            pPreset->m_sEarlyL.m_nGain[0] = 22152;
            pPreset->m_sEarlyL.m_zDelay[1] = 2163;
            pPreset->m_sEarlyL.m_nGain[1] = 17537;
            pPreset->m_sEarlyL.m_zDelay[2] = 0;
            pPreset->m_sEarlyL.m_nGain[2] = 14768;
            pPreset->m_sEarlyL.m_zDelay[3] = 1835;
            pPreset->m_sEarlyL.m_nGain[3] = 14307;
            pPreset->m_sEarlyL.m_zDelay[4] = 0;
            pPreset->m_sEarlyL.m_nGain[4] = 13384;
            pPreset->m_sEarlyR.m_zDelay[0] = 721;
            pPreset->m_sEarlyR.m_nGain[0] = 20306;
            pPreset->m_sEarlyR.m_zDelay[1] = 2621;
            pPreset->m_sEarlyR.m_nGain[1] = 17537;
            pPreset->m_sEarlyR.m_zDelay[2] = 0;
            pPreset->m_sEarlyR.m_nGain[2] = 14768;
            pPreset->m_sEarlyR.m_zDelay[3] = 0;
            pPreset->m_sEarlyR.m_nGain[3] = 16153;
            pPreset->m_sEarlyR.m_zDelay[4] = 0;
            pPreset->m_sEarlyR.m_nGain[4] = 13384;
            pPreset->m_nMaxExcursion = 127;
            pPreset->m_nXfadeInterval = 6388;
            pPreset->m_nAp0_ApGain = 15691;
            pPreset->m_nAp0_ApOut = 711;
            pPreset->m_nAp1_ApGain = 16317;
            pPreset->m_nAp1_ApOut = 1029;
            pPreset->m_rfu4 = 0;
            pPreset->m_rfu5 = 0;
            pPreset->m_rfu6 = 0;
            pPreset->m_rfu7 = 0;
            pPreset->m_rfu8 = 0;
            pPreset->m_rfu9 = 0;
            pPreset->m_rfu10 = 0;
            break;
        }
    }

    return 0;
}

audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
    .tag = AUDIO_EFFECT_LIBRARY_TAG,
    .version = EFFECT_LIBRARY_API_VERSION,
    .name = "Test Equalizer Library",
    .implementor = "The Android Open Source Project",
    .query_num_effects = EffectQueryNumberEffects,
    .query_effect = EffectQueryEffect,
    .create_effect = EffectCreate,
    .release_effect = EffectRelease,
    .get_descriptor = EffectGetDescriptor,
};
