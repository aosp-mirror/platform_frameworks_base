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
#include <audio_effects/effect_visualizer.h>


extern "C" {

// effect_handle_t interface implementation for visualizer effect
extern const struct effect_interface_s gVisualizerInterface;

// Google Visualizer UUID: d069d9e0-8329-11df-9168-0002a5d5c51b
const effect_descriptor_t gVisualizerDescriptor = {
        {0xe46b26a0, 0xdddd, 0x11db, 0x8afd, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}}, // type
        {0xd069d9e0, 0x8329, 0x11df, 0x9168, {0x00, 0x02, 0xa5, 0xd5, 0xc5, 0x1b}}, // uuid
        EFFECT_CONTROL_API_VERSION,
        (EFFECT_FLAG_TYPE_INSERT | EFFECT_FLAG_INSERT_FIRST),
        0, // TODO
        1,
        "Visualizer",
        "The Android Open Source Project",
};

enum visualizer_state_e {
    VISUALIZER_STATE_UNINITIALIZED,
    VISUALIZER_STATE_INITIALIZED,
    VISUALIZER_STATE_ACTIVE,
};

// maximum number of reads from same buffer before resetting capture buffer. This means
// that the framework has stopped playing audio and we must start returning silence
#define MAX_STALL_COUNT 10

struct VisualizerContext {
    const struct effect_interface_s *mItfe;
    effect_config_t mConfig;
    uint32_t mCaptureIdx;
    uint32_t mCaptureSize;
    uint8_t mState;
    uint8_t mCurrentBuf;
    uint8_t mLastBuf;
    uint8_t mStallCount;
    uint8_t mCaptureBuf[2][VISUALIZER_CAPTURE_SIZE_MAX];
};

//
//--- Local functions
//

void Visualizer_reset(VisualizerContext *pContext)
{
    pContext->mCaptureIdx = 0;
    pContext->mCurrentBuf = 0;
    pContext->mLastBuf = 1;
    pContext->mStallCount = 0;
    memset(pContext->mCaptureBuf[0], 0x80, VISUALIZER_CAPTURE_SIZE_MAX);
    memset(pContext->mCaptureBuf[1], 0x80, VISUALIZER_CAPTURE_SIZE_MAX);
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
    ALOGV("Visualizer_configure start");

    if (pConfig->inputCfg.samplingRate != pConfig->outputCfg.samplingRate) return -EINVAL;
    if (pConfig->inputCfg.channels != pConfig->outputCfg.channels) return -EINVAL;
    if (pConfig->inputCfg.format != pConfig->outputCfg.format) return -EINVAL;
    if (pConfig->inputCfg.channels != AUDIO_CHANNEL_OUT_STEREO) return -EINVAL;
    if (pConfig->outputCfg.accessMode != EFFECT_BUFFER_ACCESS_WRITE &&
            pConfig->outputCfg.accessMode != EFFECT_BUFFER_ACCESS_ACCUMULATE) return -EINVAL;
    if (pConfig->inputCfg.format != AUDIO_FORMAT_PCM_16_BIT) return -EINVAL;

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
    pContext->mConfig.inputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    pContext->mConfig.inputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
    pContext->mConfig.inputCfg.samplingRate = 44100;
    pContext->mConfig.inputCfg.bufferProvider.getBuffer = NULL;
    pContext->mConfig.inputCfg.bufferProvider.releaseBuffer = NULL;
    pContext->mConfig.inputCfg.bufferProvider.cookie = NULL;
    pContext->mConfig.inputCfg.mask = EFFECT_CONFIG_ALL;
    pContext->mConfig.outputCfg.accessMode = EFFECT_BUFFER_ACCESS_ACCUMULATE;
    pContext->mConfig.outputCfg.channels = AUDIO_CHANNEL_OUT_STEREO;
    pContext->mConfig.outputCfg.format = AUDIO_FORMAT_PCM_16_BIT;
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

int VisualizerLib_QueryNumberEffects(uint32_t *pNumEffects) {
    *pNumEffects = 1;
    return 0;
}

int VisualizerLib_QueryEffect(uint32_t index,
                              effect_descriptor_t *pDescriptor) {
    if (pDescriptor == NULL) {
        return -EINVAL;
    }
    if (index > 0) {
        return -EINVAL;
    }
    memcpy(pDescriptor, &gVisualizerDescriptor, sizeof(effect_descriptor_t));
    return 0;
}

int VisualizerLib_Create(effect_uuid_t *uuid,
                         int32_t sessionId,
                         int32_t ioId,
                         effect_handle_t *pHandle) {
    int ret;
    int i;

    if (pHandle == NULL || uuid == NULL) {
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
        ALOGW("VisualizerLib_Create() init failed");
        delete pContext;
        return ret;
    }

    *pHandle = (effect_handle_t)pContext;

    pContext->mState = VISUALIZER_STATE_INITIALIZED;

    ALOGV("VisualizerLib_Create %p", pContext);

    return 0;

}

int VisualizerLib_Release(effect_handle_t handle) {
    VisualizerContext * pContext = (VisualizerContext *)handle;

    ALOGV("VisualizerLib_Release %p", handle);
    if (pContext == NULL) {
        return -EINVAL;
    }
    pContext->mState = VISUALIZER_STATE_UNINITIALIZED;
    delete pContext;

    return 0;
}

int VisualizerLib_GetDescriptor(effect_uuid_t       *uuid,
                                effect_descriptor_t *pDescriptor) {

    if (pDescriptor == NULL || uuid == NULL){
        ALOGV("VisualizerLib_GetDescriptor() called with NULL pointer");
        return -EINVAL;
    }

    if (memcmp(uuid, &gVisualizerDescriptor.uuid, sizeof(effect_uuid_t)) == 0) {
        memcpy(pDescriptor, &gVisualizerDescriptor, sizeof(effect_descriptor_t));
        return 0;
    }

    return  -EINVAL;
} /* end VisualizerLib_GetDescriptor */

//
//--- Effect Control Interface Implementation
//

static inline int16_t clamp16(int32_t sample)
{
    if ((sample>>15) ^ (sample>>31))
        sample = 0x7FFF ^ (sample>>31);
    return sample;
}

int Visualizer_process(
        effect_handle_t self,audio_buffer_t *inBuffer, audio_buffer_t *outBuffer)
{
    VisualizerContext * pContext = (VisualizerContext *)self;

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

    // derive capture scaling factor from peak value in current buffer
    // this gives more interesting captures for display.
    int32_t shift = 32;
    int len = inBuffer->frameCount * 2;
    for (int i = 0; i < len; i++) {
        int32_t smp = inBuffer->s16[i];
        if (smp < 0) smp = -smp - 1; // take care to keep the max negative in range
        int32_t clz = __builtin_clz(smp);
        if (shift > clz) shift = clz;
    }
    // A maximum amplitude signal will have 17 leading zeros, which we want to
    // translate to a shift of 8 (for converting 16 bit to 8 bit)
     shift = 25 - shift;
    // Never scale by less than 8 to avoid returning unaltered PCM signal.
    if (shift < 3) {
        shift = 3;
    }
    // add one to combine the division by 2 needed after summing left and right channels below
    shift++;

    uint32_t captIdx;
    uint32_t inIdx;
    uint8_t *buf = pContext->mCaptureBuf[pContext->mCurrentBuf];
    for (inIdx = 0, captIdx = pContext->mCaptureIdx;
         inIdx < inBuffer->frameCount && captIdx < pContext->mCaptureSize;
         inIdx++, captIdx++) {
        int32_t smp = inBuffer->s16[2 * inIdx] + inBuffer->s16[2 * inIdx + 1];
        smp = smp >> shift;
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

int Visualizer_command(effect_handle_t self, uint32_t cmdCode, uint32_t cmdSize,
        void *pCmdData, uint32_t *replySize, void *pReplyData) {

    VisualizerContext * pContext = (VisualizerContext *)self;
    int retsize;

    if (pContext == NULL || pContext->mState == VISUALIZER_STATE_UNINITIALIZED) {
        return -EINVAL;
    }

//    ALOGV("Visualizer_command command %d cmdSize %d",cmdCode, cmdSize);

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
        ALOGV("EFFECT_CMD_ENABLE() OK");
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
        ALOGV("EFFECT_CMD_DISABLE() OK");
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
            *(uint32_t *)p->data != VISUALIZER_PARAM_CAPTURE_SIZE) {
            p->status = -EINVAL;
            break;
        }
        ALOGV("get mCaptureSize = %d", pContext->mCaptureSize);
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
            *(uint32_t *)p->data != VISUALIZER_PARAM_CAPTURE_SIZE) {
            *(int32_t *)pReplyData = -EINVAL;
            break;;
        }
        pContext->mCaptureSize = *((uint32_t *)p->data + 1);
        ALOGV("set mCaptureSize = %d", pContext->mCaptureSize);
        } break;
    case EFFECT_CMD_SET_DEVICE:
    case EFFECT_CMD_SET_VOLUME:
    case EFFECT_CMD_SET_AUDIO_MODE:
        break;


    case VISUALIZER_CMD_CAPTURE:
        if (pReplyData == NULL || *replySize != pContext->mCaptureSize) {
            ALOGV("VISUALIZER_CMD_CAPTURE() error *replySize %d pContext->mCaptureSize %d",
                    *replySize, pContext->mCaptureSize);
            return -EINVAL;
        }
        if (pContext->mState == VISUALIZER_STATE_ACTIVE) {
            memcpy(pReplyData,
                   pContext->mCaptureBuf[pContext->mCurrentBuf ^ 1],
                   pContext->mCaptureSize);
            // if audio framework has stopped playing audio although the effect is still
            // active we must clear the capture buffer to return silence
            if (pContext->mLastBuf == pContext->mCurrentBuf) {
                if (pContext->mStallCount < MAX_STALL_COUNT) {
                    if (++pContext->mStallCount == MAX_STALL_COUNT) {
                        memset(pContext->mCaptureBuf[pContext->mCurrentBuf ^ 1],
                                0x80,
                                pContext->mCaptureSize);
                    }
                }
            } else {
                pContext->mStallCount = 0;
            }
            pContext->mLastBuf = pContext->mCurrentBuf;
        } else {
            memset(pReplyData, 0x80, pContext->mCaptureSize);
        }

        break;

    default:
        ALOGW("Visualizer_command invalid command %d",cmdCode);
        return -EINVAL;
    }

    return 0;
}

/* Effect Control Interface Implementation: get_descriptor */
int Visualizer_getDescriptor(effect_handle_t   self,
                                    effect_descriptor_t *pDescriptor)
{
    VisualizerContext * pContext = (VisualizerContext *) self;

    if (pContext == NULL || pDescriptor == NULL) {
        ALOGV("Visualizer_getDescriptor() invalid param");
        return -EINVAL;
    }

    memcpy(pDescriptor, &gVisualizerDescriptor, sizeof(effect_descriptor_t));

    return 0;
}   /* end Visualizer_getDescriptor */

// effect_handle_t interface implementation for visualizer effect
const struct effect_interface_s gVisualizerInterface = {
        Visualizer_process,
        Visualizer_command,
        Visualizer_getDescriptor,
        NULL,
};


audio_effect_library_t AUDIO_EFFECT_LIBRARY_INFO_SYM = {
    tag : AUDIO_EFFECT_LIBRARY_TAG,
    version : EFFECT_LIBRARY_API_VERSION,
    name : "Visualizer Library",
    implementor : "The Android Open Source Project",
    query_num_effects : VisualizerLib_QueryNumberEffects,
    query_effect : VisualizerLib_QueryEffect,
    create_effect : VisualizerLib_Create,
    release_effect : VisualizerLib_Release,
    get_descriptor : VisualizerLib_GetDescriptor,
};

}; // extern "C"
