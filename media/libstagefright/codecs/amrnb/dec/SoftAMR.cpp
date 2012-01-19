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
#define LOG_TAG "SoftAMR"
#include <utils/Log.h>

#include "SoftAMR.h"

#include "gsmamr_dec.h"
#include "pvamrwbdecoder.h"

#include <media/stagefright/foundation/ADebug.h>

namespace android {

template<class T>
static void InitOMXParams(T *params) {
    params->nSize = sizeof(T);
    params->nVersion.s.nVersionMajor = 1;
    params->nVersion.s.nVersionMinor = 0;
    params->nVersion.s.nRevision = 0;
    params->nVersion.s.nStep = 0;
}

SoftAMR::SoftAMR(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component)
    : SimpleSoftOMXComponent(name, callbacks, appData, component),
      mMode(MODE_NARROW),
      mState(NULL),
      mDecoderBuf(NULL),
      mDecoderCookie(NULL),
      mInputBufferCount(0),
      mAnchorTimeUs(0),
      mNumSamplesOutput(0),
      mSignalledError(false),
      mOutputPortSettingsChange(NONE) {
    if (!strcmp(name, "OMX.google.amrwb.decoder")) {
        mMode = MODE_WIDE;
    } else {
        CHECK(!strcmp(name, "OMX.google.amrnb.decoder"));
    }

    initPorts();
    CHECK_EQ(initDecoder(), (status_t)OK);
}

SoftAMR::~SoftAMR() {
    if (mMode == MODE_NARROW) {
        GSMDecodeFrameExit(&mState);
        mState = NULL;
    } else {
        free(mDecoderBuf);
        mDecoderBuf = NULL;

        mState = NULL;
        mDecoderCookie = NULL;
    }
}

void SoftAMR::initPorts() {
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
        mMode == MODE_NARROW
            ? const_cast<char *>("audio/amr")
            : const_cast<char *>("audio/amrwb");

    def.format.audio.pNativeRender = NULL;
    def.format.audio.bFlagErrorConcealment = OMX_FALSE;
    def.format.audio.eEncoding = OMX_AUDIO_CodingAMR;

    addPort(def);

    def.nPortIndex = 1;
    def.eDir = OMX_DirOutput;
    def.nBufferCountMin = kNumBuffers;
    def.nBufferCountActual = def.nBufferCountMin;

    def.nBufferSize =
        (mMode == MODE_NARROW ? kNumSamplesPerFrameNB : kNumSamplesPerFrameWB)
            * sizeof(int16_t);

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

status_t SoftAMR::initDecoder() {
    if (mMode == MODE_NARROW) {
        Word16 err = GSMInitDecode(&mState, (Word8 *)"AMRNBDecoder");

        if (err != 0) {
            return UNKNOWN_ERROR;
        }
    } else {
        int32_t memReq = pvDecoder_AmrWbMemRequirements();
        mDecoderBuf = malloc(memReq);

        pvDecoder_AmrWb_Init(&mState, mDecoderBuf, &mDecoderCookie);
    }

    return OK;
}

OMX_ERRORTYPE SoftAMR::internalGetParameter(
        OMX_INDEXTYPE index, OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamAudioAmr:
        {
            OMX_AUDIO_PARAM_AMRTYPE *amrParams =
                (OMX_AUDIO_PARAM_AMRTYPE *)params;

            if (amrParams->nPortIndex != 0) {
                return OMX_ErrorUndefined;
            }

            amrParams->nChannels = 1;
            amrParams->eAMRDTXMode = OMX_AUDIO_AMRDTXModeOff;
            amrParams->eAMRFrameFormat = OMX_AUDIO_AMRFrameFormatConformance;

            if (!isConfigured()) {
                amrParams->nBitRate = 0;
                amrParams->eAMRBandMode = OMX_AUDIO_AMRBandModeUnused;
            } else {
                amrParams->nBitRate = 0;
                amrParams->eAMRBandMode =
                    mMode == MODE_NARROW
                        ? OMX_AUDIO_AMRBandModeNB0 : OMX_AUDIO_AMRBandModeWB0;
            }

            return OMX_ErrorNone;
        }

        case OMX_IndexParamAudioPcm:
        {
            OMX_AUDIO_PARAM_PCMMODETYPE *pcmParams =
                (OMX_AUDIO_PARAM_PCMMODETYPE *)params;

            if (pcmParams->nPortIndex != 1) {
                return OMX_ErrorUndefined;
            }

            pcmParams->nChannels = 1;
            pcmParams->eNumData = OMX_NumericalDataSigned;
            pcmParams->eEndian = OMX_EndianBig;
            pcmParams->bInterleaved = OMX_TRUE;
            pcmParams->nBitPerSample = 16;

            pcmParams->nSamplingRate =
                (mMode == MODE_NARROW) ? kSampleRateNB : kSampleRateWB;

            pcmParams->ePCMMode = OMX_AUDIO_PCMModeLinear;
            pcmParams->eChannelMapping[0] = OMX_AUDIO_ChannelLF;
            pcmParams->eChannelMapping[1] = OMX_AUDIO_ChannelRF;

            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalGetParameter(index, params);
    }
}

OMX_ERRORTYPE SoftAMR::internalSetParameter(
        OMX_INDEXTYPE index, const OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamStandardComponentRole:
        {
            const OMX_PARAM_COMPONENTROLETYPE *roleParams =
                (const OMX_PARAM_COMPONENTROLETYPE *)params;

            if (mMode == MODE_NARROW) {
                if (strncmp((const char *)roleParams->cRole,
                            "audio_decoder.amrnb",
                            OMX_MAX_STRINGNAME_SIZE - 1)) {
                    return OMX_ErrorUndefined;
                }
            } else {
                if (strncmp((const char *)roleParams->cRole,
                            "audio_decoder.amrwb",
                            OMX_MAX_STRINGNAME_SIZE - 1)) {
                    return OMX_ErrorUndefined;
                }
            }

            return OMX_ErrorNone;
        }

        case OMX_IndexParamAudioAmr:
        {
            const OMX_AUDIO_PARAM_AMRTYPE *aacParams =
                (const OMX_AUDIO_PARAM_AMRTYPE *)params;

            if (aacParams->nPortIndex != 0) {
                return OMX_ErrorUndefined;
            }

            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalSetParameter(index, params);
    }
}

bool SoftAMR::isConfigured() const {
    return mInputBufferCount > 0;
}

static size_t getFrameSize(unsigned FT) {
    static const size_t kFrameSizeWB[9] = {
        132, 177, 253, 285, 317, 365, 397, 461, 477
    };

    size_t frameSize = kFrameSizeWB[FT];

    // Round up bits to bytes and add 1 for the header byte.
    frameSize = (frameSize + 7) / 8 + 1;

    return frameSize;
}

void SoftAMR::onQueueFilled(OMX_U32 portIndex) {
    List<BufferInfo *> &inQueue = getPortQueue(0);
    List<BufferInfo *> &outQueue = getPortQueue(1);

    if (mSignalledError || mOutputPortSettingsChange != NONE) {
        return;
    }

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
            mNumSamplesOutput = 0;
        }

        const uint8_t *inputPtr = inHeader->pBuffer + inHeader->nOffset;
        int32_t numBytesRead;

        if (mMode == MODE_NARROW) {
            numBytesRead =
                AMRDecode(mState,
                  (Frame_Type_3GPP)((inputPtr[0] >> 3) & 0x0f),
                  (UWord8 *)&inputPtr[1],
                  reinterpret_cast<int16_t *>(outHeader->pBuffer),
                  MIME_IETF);

            if (numBytesRead == -1) {
                ALOGE("PV AMR decoder AMRDecode() call failed");

                notify(OMX_EventError, OMX_ErrorUndefined, 0, NULL);
                mSignalledError = true;

                return;
            }

            ++numBytesRead;  // Include the frame type header byte.

            if (static_cast<size_t>(numBytesRead) > inHeader->nFilledLen) {
                // This is bad, should never have happened, but did. Abort now.

                notify(OMX_EventError, OMX_ErrorUndefined, 0, NULL);
                mSignalledError = true;

                return;
            }
        } else {
            int16 mode = ((inputPtr[0] >> 3) & 0x0f);
            size_t frameSize = getFrameSize(mode);
            CHECK_GE(inHeader->nFilledLen, frameSize);

            int16 frameType;
            RX_State_wb rx_state;
            mime_unsorting(
                    const_cast<uint8_t *>(&inputPtr[1]),
                    mInputSampleBuffer,
                    &frameType, &mode, 1, &rx_state);

            int16_t *outPtr = (int16_t *)outHeader->pBuffer;

            int16_t numSamplesOutput;
            pvDecoder_AmrWb(
                    mode, mInputSampleBuffer,
                    outPtr,
                    &numSamplesOutput,
                    mDecoderBuf, frameType, mDecoderCookie);

            CHECK_EQ((int)numSamplesOutput, (int)kNumSamplesPerFrameWB);

            for (int i = 0; i < kNumSamplesPerFrameWB; ++i) {
                /* Delete the 2 LSBs (14-bit output) */
                outPtr[i] &= 0xfffC;
            }

            numBytesRead = frameSize;
        }

        inHeader->nOffset += numBytesRead;
        inHeader->nFilledLen -= numBytesRead;

        outHeader->nFlags = 0;
        outHeader->nOffset = 0;

        if (mMode == MODE_NARROW) {
            outHeader->nFilledLen = kNumSamplesPerFrameNB * sizeof(int16_t);

            outHeader->nTimeStamp =
                mAnchorTimeUs
                    + (mNumSamplesOutput * 1000000ll) / kSampleRateNB;

            mNumSamplesOutput += kNumSamplesPerFrameNB;
        } else {
            outHeader->nFilledLen = kNumSamplesPerFrameWB * sizeof(int16_t);

            outHeader->nTimeStamp =
                mAnchorTimeUs
                    + (mNumSamplesOutput * 1000000ll) / kSampleRateWB;

            mNumSamplesOutput += kNumSamplesPerFrameWB;
        }

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

        ++mInputBufferCount;
    }
}

void SoftAMR::onPortFlushCompleted(OMX_U32 portIndex) {
}

void SoftAMR::onPortEnableCompleted(OMX_U32 portIndex, bool enabled) {
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
    return new android::SoftAMR(name, callbacks, appData, component);
}

