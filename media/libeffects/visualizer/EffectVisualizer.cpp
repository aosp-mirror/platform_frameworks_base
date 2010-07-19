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

#define LOG_TAG "Visualizer"
//#define LOG_NDEBUG 0
#include <cutils/log.h>
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include <new>
#include <media/EffectVisualizerApi.h>

namespace android {

// effect_interface_t interface implementation for visualizer effect
extern "C" const struct effect_interface_s gVisualizerInterface;

// Google Visualizer UUID: d069d9e0-8329-11df-9168-0002a5d5c51b
const effect_descriptor_t gVisualizerDescriptor = {
        {0xe46b26a0, 0xdddd, 0x11db, 0x8afd, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}}, // type
        {0xd069d9e0, 0x8329, 0x11df, 0x9168, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}}, // uuid
        EFFECT_API_VERSION,
        (EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_FIRST),
        0, // TODO
        1,
        "Visualizer",
        "Google Inc.",
};

enum visualizer_state_e {
    VISUALIZER_STATE_UNINITIALIZED,
    VISUALIZER_STATE_INITIALIZED,
    VISUALIZER_STATE_ACTIVE,
};

struct VisualizerContext {
    const struct effect_interface_s *mItfe;
    effect_config_t mConfig;
    uint32_t mState;
    uint32_t mCaptureIdx;
    uint32_t mCaptureSize;
    uint32_t mCurrentBuf;
    uint8_t mCaptureBuf[2][VISUALIZER_CAPTURE_SIZE_MAX];
};


//
//--- Local functions
//

void Visualizer_reset(VisualizerContext *pContext)
{
    pContext->mCaptureIdx = 0;
    pContext->mCurrentBuf = 0;
    memset(pContext->mCaptureBuf[0], 0, VISUALIZER_CAPTURE_SIZE_MAX);
    memset(pContext->mCaptureBuf[1], 0, VISUALIZER_CAPTURE_SIZE_MAX);
}

//----------------------------------------------------------------------------
// Visualizer_configure()
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

int Visualizer_configure(VisualizerContext *pContext, effect_config_t *pConfig)
{
    LOGV("Visualizer_configure start");

    if (pConfig->inputCfg.samplingRate != pConfig->outputCfg.samplingRate) return -EINVAL;
    if (pConfig->inputCfg.channels != pConfig->outputCfg.channels) return -EINVAL;
    if (pConfig->inputCfg.format != pConfig->outputCfg.format) return -EINVAL;
    if (pConfig->inputCfg.channels != CHANNEL_STEREO) return -EINVAL;
    if (pConfig->outputCfg.accessMode != EFFECT_BUFFER_ACCESS_WRITE &&
            pConfig->outputCfg.accessMode != EFFECT_BUFFER_ACCESS_ACCUMULATE) return -EINVAL;
    if (pConfig->inputCfg.format != SAMPLE_FORMAT_PCM_S15) return -EINVAL;

    memcpy(&pContext->mConfig, pConfig, sizeof(effect_config_t));

    Visualizer_reset(pContext);

    return 0;
}


//----------------------------------------------------------------------------
// Visualizer_init()
//----------------------------------------------------------------------------
// Purpose: Initialize engine with default configuration.
//
// Inputs:
//  pContext:   effect engine context
//
// Outputs:
//
//----------------------------------------------------------------------------

int Visualizer_init(VisualizerContext *pContext)
{
    pContext->mConfig.inputCfg.accessMode = EFFECT_BUFFER_ACCESS_READ;
    pContext->mConfig.inputCfg.channels = CHANNEL_STEREO;
    pContext->mConfig.inputCfg.format = SAMPLE_FORMAT_PCM_S15;
    pContext->mConfig.inputCfg.samplingRate = 44100;
    pContext->mConfig.inputCfg.bufferProvider.getBuffer = NULL;
    pContext->mConfig.inputCfg.bufferProvider.releaseBuffer = NULL;
    pContext->mConfig.inputCfg.bufferProvider.cookie = NULL;
    pContext->mConfig.inputCfg.mask = EFFECT_CONFIG_ALL;
    pContext->mConfig.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_ACCUMULATE;
    pContext->mConfig.outputCfg.channels = CHANNEL_STEREO;
    pContext->mConfig.outputCfg.format = SAMPLE_FORMAT_PCM_S15;
    pContext->mConfig.outputCfg.samplingRate = 44100;
    pContext->mConfig.outputCfg.bufferProvider.getBuffer = NULL;
    pContext->mConfig.outputCfg.bufferProvider.releaseBuffer = NULL;
    pContext->mConfig.outputCfg.bufferProvider.cookie = NULL;
    pContext->mConfig.outputCfg.mask = EFFECT_CONFIG_ALL;

    pContext->mCaptureSize = VISUALIZER_CAPTURE_SIZE_MAX;

    Visualizer_configure(pContext, &pContext->mConfig);

    return 0;
}

//
//--- Effect Library Interface Implementation
//

extern "C" int EffectQueryNumberEffects(uint32_t *pNumEffects) {
    *pNumEffects = 1;
    return 0;
}

extern "C" int EffectQueryEffect(uint32_t index, effect_descriptor_t *pDescriptor) {
    if (pDescriptor == NULL) {
        return -EINVAL;
    }
    if (index > 0) {
        return -EINVAL;
    }
    memcpy(pDescriptor, &gVisualizerDescriptor, sizeof(effect_descriptor_t));
    return 0;
}

extern "C" int EffectCreate(effect_uuid_t *uuid,
        int32_t sessionId,
        int32_t ioId,
        effect_interface_t *pInterface) {
    int ret;
    int i;

    if (pInterface == NULL || uuid == NULL) {
        return -EINVAL;
    }

    if (memcmp(uuid, &gVisualizerDescriptor.uuid, sizeof(effect_uuid_t)) != 0) {
        return -EINVAL;
    }

    VisualizerContext *pContext = new VisualizerContext;

    pContext->mItfe = &gVisualizerInterface;
    pContext->mState = VISUALIZER_STATE_UNINITIALIZED;

    ret = Visualizer_init(pContext);
    if (ret < 0) {
        LOGW("EffectCreate() init failed");
        delete pContext;
        return ret;
    }

    *pInterface = (effect_interface_t)pContext;

    pContext->mState = VISUALIZER_STATE_INITIALIZED;

    LOGV("EffectCreate %p", pContext);

    return 0;

}

extern "C" int EffectRelease(effect_interface_t interface) {
    VisualizerContext * pContext = (VisualizerContext *)interface;

    LOGV("EffectRelease %p", interface);
    if (pContext == NULL) {
        return -EINVAL;
    }
    pContext->mState = VISUALIZER_STATE_UNINITIALIZED;
    delete pContext;

    return 0;
}

//
//--- Effect Control Interface Implementation
//

static inline int16_t clamp16(int32_t sample)
{
    if ((sample>>15) ^ (sample>>31))
        sample = 0x7FFF ^ (sample>>31);
    return sample;
}

extern "C" int Visualizer_process(
        effect_interface_t self,audio_buffer_t *inBuffer, audio_buffer_t *outBuffer)
{
    android::VisualizerContext * pContext = (android::VisualizerContext *)self;

    if (pContext == NULL) {
        return -EINVAL;
    }

    if (inBuffer == NULL || inBuffer->raw == NULL ||
        outBuffer == NULL || outBuffer->raw == NULL ||
        inBuffer->frameCount != outBuffer->frameCount ||
        inBuffer->frameCount == 0) {
        return -EINVAL;
    }

    // all code below assumes stereo 16 bit PCM output and input
    uint32_t captIdx;
    uint32_t inIdx;
    uint8_t *buf = pContext->mCaptureBuf[pContext->mCurrentBuf];
    for (inIdx = 0, captIdx = pContext->mCaptureIdx;
         inIdx < inBuffer->frameCount && captIdx < pContext->mCaptureSize;
         inIdx++, captIdx++) {
        int32_t smp = inBuffer->s16[2 * inIdx] + inBuffer->s16[2 * inIdx + 1];
        smp = (smp + (1 << 8)) >> 9;
        buf[captIdx] = ((uint8_t)smp)^0x80;
    }
    pContext->mCaptureIdx = captIdx;

    // go to next buffer when buffer full
    if (pContext->mCaptureIdx == pContext->mCaptureSize) {
        pContext->mCurrentBuf ^= 1;
        pContext->mCaptureIdx = 0;
    }

    if (inBuffer->raw != outBuffer->raw) {
        if (pContext->mConfig.outputCfg.accessMode == EFFECT_BUFFER_ACCESS_ACCUMULATE) {
            for (size_t i = 0; i < outBuffer->frameCount*2; i++) {
                outBuffer->s16[i] = clamp16(outBuffer->s16[i] + inBuffer->s16[i]);
            }
        } else {
            memcpy(outBuffer->raw, inBuffer->raw, outBuffer->frameCount * 2 * sizeof(int16_t));
        }
    }
    if (pContext->mState != VISUALIZER_STATE_ACTIVE) {
        return -ENODATA;
    }
    return 0;
}   // end Visualizer_process

extern "C" int Visualizer_command(effect_interface_t self, int cmdCode, int cmdSize,
        void *pCmdData, int *replySize, void *pReplyData) {

    android::VisualizerContext * pContext = (android::VisualizerContext *)self;
    int retsize;

    if (pContext == NULL || pContext->mState == VISUALIZER_STATE_UNINITIALIZED) {
        return -EINVAL;
    }

//    LOGV("Visualizer_command command %d cmdSize %d",cmdCode, cmdSize);

    switch (cmdCode) {
    case EFFECT_CMD_INIT:
        if (pReplyData == NULL || *replySize != sizeof(int)) {
            return -EINVAL;
        }
        *(int *) pReplyData = Visualizer_init(pContext);
        break;
    case EFFECT_CMD_CONFIGURE:
        if (pCmdData == NULL || cmdSize != sizeof(effect_config_t)
                || pReplyData == NULL || *replySize != sizeof(int)) {
            return -EINVAL;
        }
        *(int *) pReplyData = Visualizer_configure(pContext,
                (effect_config_t *) pCmdData);
        break;
    case EFFECT_CMD_RESET:
        Visualizer_reset(pContext);
        break;
    case EFFECT_CMD_ENABLE:
        if (pReplyData == NULL || *replySize != sizeof(int)) {
            return -EINVAL;
        }
        if (pContext->mState != VISUALIZER_STATE_INITIALIZED) {
            return -ENOSYS;
        }
        pContext->mState = VISUALIZER_STATE_ACTIVE;
        LOGV("EFFECT_CMD_ENABLE() OK");
        *(int *)pReplyData = 0;
        break;
    case EFFECT_CMD_DISABLE:
        if (pReplyData == NULL || *replySize != sizeof(int)) {
            return -EINVAL;
        }
        if (pContext->mState != VISUALIZER_STATE_ACTIVE) {
            return -ENOSYS;
        }
        pContext->mState = VISUALIZER_STATE_INITIALIZED;
        LOGV("EFFECT_CMD_DISABLE() OK");
        *(int *)pReplyData = 0;
        break;
    case EFFECT_CMD_GET_PARAM: {
        if (pCmdData == NULL ||
            cmdSize != (int)(sizeof(effect_param_t) + sizeof(uint32_t)) ||
            pReplyData == NULL ||
            *replySize < (int)(sizeof(effect_param_t) + sizeof(uint32_t) + sizeof(uint32_t))) {
            return -EINVAL;
        }
        memcpy(pReplyData, pCmdData, sizeof(effect_param_t) + sizeof(uint32_t));
        effect_param_t *p = (effect_param_t *)pReplyData;
        p->status = 0;
        *replySize = sizeof(effect_param_t) + sizeof(uint32_t);
        if (p->psize != sizeof(uint32_t) ||
            *(uint32_t *)p->data != VISU_PARAM_CAPTURE_SIZE) {
            p->status = -EINVAL;
            break;
        }
        LOGV("get mCaptureSize = %d", pContext->mCaptureSize);
        *((uint32_t *)p->data + 1) = pContext->mCaptureSize;
        p->vsize = sizeof(uint32_t);
        *replySize += sizeof(uint32_t);
        } break;
    case EFFECT_CMD_SET_PARAM: {
        if (pCmdData == NULL ||
            cmdSize != (int)(sizeof(effect_param_t) + sizeof(uint32_t) + sizeof(uint32_t)) ||
            pReplyData == NULL || *replySize != sizeof(int32_t)) {
            return -EINVAL;
        }
        *(int32_t *)pReplyData = 0;
        effect_param_t *p = (effect_param_t *)pCmdData;
        if (p->psize != sizeof(uint32_t) ||
            p->vsize != sizeof(uint32_t) ||
            *(uint32_t *)p->data != VISU_PARAM_CAPTURE_SIZE) {
            *(int32_t *)pReplyData = -EINVAL;
            break;;
        }
        pContext->mCaptureSize = *((uint32_t *)p->data + 1);
        LOGV("set mCaptureSize = %d", pContext->mCaptureSize);
        } break;
    case EFFECT_CMD_SET_DEVICE:
    case EFFECT_CMD_SET_VOLUME:
    case EFFECT_CMD_SET_AUDIO_MODE:
        break;


    case VISU_CMD_CAPTURE:
        if (pReplyData == NULL || *replySize != (int)pContext->mCaptureSize) {
            LOGV("VISU_CMD_CAPTURE() error *replySize %d pContext->mCaptureSize %d",
                    *replySize, pContext->mCaptureSize);
            return -EINVAL;
        }
        if (pContext->mState == VISUALIZER_STATE_ACTIVE) {
            memcpy(pReplyData,
                   pContext->mCaptureBuf[pContext->mCurrentBuf ^ 1],
                   pContext->mCaptureSize);
        } else {
            memset(pReplyData, 0x80, pContext->mCaptureSize);
        }
        break;

    default:
        LOGW("Visualizer_command invalid command %d",cmdCode);
        return -EINVAL;
    }

    return 0;
}

// effect_interface_t interface implementation for visualizer effect
const struct effect_interface_s gVisualizerInterface = {
        Visualizer_process,
        Visualizer_command
};

} // namespace

