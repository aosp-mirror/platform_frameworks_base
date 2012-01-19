/*
 * Copyright (C) 2011 The Android Open Source Project
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

//#define LOG_NDEBUG 0
#define LOG_TAG "SoftMP3"
#include <utils/Log.h>

#include "SoftMP3.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaDefs.h>

#include "include/pvmp3decoder_api.h"

namespace android {

template<class T>
static void InitOMXParams(T *params) {
    params->nSize = sizeof(T);
    params->nVersion.s.nVersionMajor = 1;
    params->nVersion.s.nVersionMinor = 0;
    params->nVersion.s.nRevision = 0;
    params->nVersion.s.nStep = 0;
}

SoftMP3::SoftMP3(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component)
    : SimpleSoftOMXComponent(name, callbacks, appData, component),
      mConfig(new tPVMP3DecoderExternal),
      mDecoderBuf(NULL),
      mAnchorTimeUs(0),
      mNumFramesOutput(0),
      mNumChannels(2),
      mSamplingRate(44100),
      mSignalledError(false),
      mOutputPortSettingsChange(NONE) {
    initPorts();
    initDecoder();
}

SoftMP3::~SoftMP3() {
    if (mDecoderBuf != NULL) {
        free(mDecoderBuf);
        mDecoderBuf = NULL;
    }

    delete mConfig;
    mConfig = NULL;
}

void SoftMP3::initPorts() {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    InitOMXParams(&def);

    def.nPortIndex = 0;
    def.eDir = OMX_DirInput;
    def.nBufferCountMin = kNumBuffers;
    def.nBufferCountActual = def.nBufferCountMin;
    def.nBufferSize = 8192;
    def.bEnabled = OMX_TRUE;
    def.bPopulated = OMX_FALSE;
    def.eDomain = OMX_PortDomainAudio;
    def.bBuffersContiguous = OMX_FALSE;
    def.nBufferAlignment = 1;

    def.format.audio.cMIMEType =
        const_cast<char *>(MEDIA_MIMETYPE_AUDIO_MPEG);

    def.format.audio.pNativeRender = NULL;
    def.format.audio.bFlagErrorConcealment = OMX_FALSE;
    def.format.audio.eEncoding = OMX_AUDIO_CodingMP3;

    addPort(def);

    def.nPortIndex = 1;
    def.eDir = OMX_DirOutput;
    def.nBufferCountMin = kNumBuffers;
    def.nBufferCountActual = def.nBufferCountMin;
    def.nBufferSize = kOutputBufferSize;
    def.bEnabled = OMX_TRUE;
    def.bPopulated = OMX_FALSE;
    def.eDomain = OMX_PortDomainAudio;
    def.bBuffersContiguous = OMX_FALSE;
    def.nBufferAlignment = 2;

    def.format.audio.cMIMEType = const_cast<char *>("audio/raw");
    def.format.audio.pNativeRender = NULL;
    def.format.audio.bFlagErrorConcealment = OMX_FALSE;
    def.format.audio.eEncoding = OMX_AUDIO_CodingPCM;

    addPort(def);
}

void SoftMP3::initDecoder() {
    mConfig->equalizerType = flat;
    mConfig->crcEnabled = false;

    uint32_t memRequirements = pvmp3_decoderMemRequirements();
    mDecoderBuf = malloc(memRequirements);

    pvmp3_InitDecoder(mConfig, mDecoderBuf);
}

OMX_ERRORTYPE SoftMP3::internalGetParameter(
        OMX_INDEXTYPE index, OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamAudioPcm:
        {
            OMX_AUDIO_PARAM_PCMMODETYPE *pcmParams =
                (OMX_AUDIO_PARAM_PCMMODETYPE *)params;

            if (pcmParams->nPortIndex > 1) {
                return OMX_ErrorUndefined;
            }

            pcmParams->eNumData = OMX_NumericalDataSigned;
            pcmParams->eEndian = OMX_EndianBig;
            pcmParams->bInterleaved = OMX_TRUE;
            pcmParams->nBitPerSample = 16;
            pcmParams->ePCMMode = OMX_AUDIO_PCMModeLinear;
            pcmParams->eChannelMapping[0] = OMX_AUDIO_ChannelLF;
            pcmParams->eChannelMapping[1] = OMX_AUDIO_ChannelRF;

            pcmParams->nChannels = mNumChannels;
            pcmParams->nSamplingRate = mSamplingRate;

            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalGetParameter(index, params);
    }
}

OMX_ERRORTYPE SoftMP3::internalSetParameter(
        OMX_INDEXTYPE index, const OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamStandardComponentRole:
        {
            const OMX_PARAM_COMPONENTROLETYPE *roleParams =
                (const OMX_PARAM_COMPONENTROLETYPE *)params;

            if (strncmp((const char *)roleParams->cRole,
                        "audio_decoder.mp3",
                        OMX_MAX_STRINGNAME_SIZE - 1)) {
                return OMX_ErrorUndefined;
            }

            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalSetParameter(index, params);
    }
}

void SoftMP3::onQueueFilled(OMX_U32 portIndex) {
    if (mSignalledError || mOutputPortSettingsChange != NONE) {
        return;
    }

    List<BufferInfo *> &inQueue = getPortQueue(0);
    List<BufferInfo *> &outQueue = getPortQueue(1);

    while (!inQueue.empty() && !outQueue.empty()) {
        BufferInfo *inInfo = *inQueue.begin();
        OMX_BUFFERHEADERTYPE *inHeader = inInfo->mHeader;

        BufferInfo *outInfo = *outQueue.begin();
        OMX_BUFFERHEADERTYPE *outHeader = outInfo->mHeader;

        if (inHeader->nFlags & OMX_BUFFERFLAG_EOS) {
            inQueue.erase(inQueue.begin());
            inInfo->mOwnedByUs = false;
            notifyEmptyBufferDone(inHeader);

            outHeader->nFilledLen = 0;
            outHeader->nFlags = OMX_BUFFERFLAG_EOS;

            outQueue.erase(outQueue.begin());
            outInfo->mOwnedByUs = false;
            notifyFillBufferDone(outHeader);
            return;
        }

        if (inHeader->nOffset == 0) {
            mAnchorTimeUs = inHeader->nTimeStamp;
            mNumFramesOutput = 0;
        }

        mConfig->pInputBuffer =
            inHeader->pBuffer + inHeader->nOffset;

        mConfig->inputBufferCurrentLength = inHeader->nFilledLen;
        mConfig->inputBufferMaxLength = 0;
        mConfig->inputBufferUsedLength = 0;

        mConfig->outputFrameSize = kOutputBufferSize / sizeof(int16_t);

        mConfig->pOutputBuffer =
            reinterpret_cast<int16_t *>(outHeader->pBuffer);

        ERROR_CODE decoderErr;
        if ((decoderErr = pvmp3_framedecoder(mConfig, mDecoderBuf))
                != NO_DECODING_ERROR) {
            ALOGV("mp3 decoder returned error %d", decoderErr);

            if (decoderErr != NO_ENOUGH_MAIN_DATA_ERROR ||
                    mConfig->outputFrameSize == 0) {
                ALOGE("mp3 decoder returned error %d", decoderErr);

                if (mConfig->outputFrameSize == 0) {
                    ALOGE("Output frame size is 0");
                }

                notify(OMX_EventError, OMX_ErrorUndefined, decoderErr, NULL);
                mSignalledError = true;
                return;
            }

            // This is recoverable, just ignore the current frame and
            // play silence instead.
            memset(outHeader->pBuffer,
                   0,
                   mConfig->outputFrameSize * sizeof(int16_t));

            mConfig->inputBufferUsedLength = inHeader->nFilledLen;
        } else if (mConfig->samplingRate != mSamplingRate
                || mConfig->num_channels != mNumChannels) {
            mSamplingRate = mConfig->samplingRate;
            mNumChannels = mConfig->num_channels;

            notify(OMX_EventPortSettingsChanged, 1, 0, NULL);
            mOutputPortSettingsChange = AWAITING_DISABLED;
            return;
        }

        outHeader->nOffset = 0;
        outHeader->nFilledLen = mConfig->outputFrameSize * sizeof(int16_t);

        outHeader->nTimeStamp =
            mAnchorTimeUs
                + (mNumFramesOutput * 1000000ll) / mConfig->samplingRate;

        outHeader->nFlags = 0;

        CHECK_GE(inHeader->nFilledLen, mConfig->inputBufferUsedLength);

        inHeader->nOffset += mConfig->inputBufferUsedLength;
        inHeader->nFilledLen -= mConfig->inputBufferUsedLength;

        mNumFramesOutput += mConfig->outputFrameSize / mNumChannels;

        if (inHeader->nFilledLen == 0) {
            inInfo->mOwnedByUs = false;
            inQueue.erase(inQueue.begin());
            inInfo = NULL;
            notifyEmptyBufferDone(inHeader);
            inHeader = NULL;
        }

        outInfo->mOwnedByUs = false;
        outQueue.erase(outQueue.begin());
        outInfo = NULL;
        notifyFillBufferDone(outHeader);
        outHeader = NULL;
    }
}

void SoftMP3::onPortFlushCompleted(OMX_U32 portIndex) {
    if (portIndex == 0) {
        // Make sure that the next buffer output does not still
        // depend on fragments from the last one decoded.
        pvmp3_InitDecoder(mConfig, mDecoderBuf);
    }
}

void SoftMP3::onPortEnableCompleted(OMX_U32 portIndex, bool enabled) {
    if (portIndex != 1) {
        return;
    }

    switch (mOutputPortSettingsChange) {
        case NONE:
            break;

        case AWAITING_DISABLED:
        {
            CHECK(!enabled);
            mOutputPortSettingsChange = AWAITING_ENABLED;
            break;
        }

        default:
        {
            CHECK_EQ((int)mOutputPortSettingsChange, (int)AWAITING_ENABLED);
            CHECK(enabled);
            mOutputPortSettingsChange = NONE;
            break;
        }
    }
}

}  // namespace android

android::SoftOMXComponent *createSoftOMXComponent(
        const char *name, const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData, OMX_COMPONENTTYPE **component) {
    return new android::SoftMP3(name, callbacks, appData, component);
}
