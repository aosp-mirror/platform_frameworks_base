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

//#define LOG_NDEBUG 0
#define LOG_TAG "SoftAMRNBEncoder"
#include <utils/Log.h>

#include "SoftAMRNBEncoder.h"

#include "gsmamr_enc.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/hexdump.h>

namespace android {

static const int32_t kSampleRate = 8000;

template<class T>
static void InitOMXParams(T *params) {
    params->nSize = sizeof(T);
    params->nVersion.s.nVersionMajor = 1;
    params->nVersion.s.nVersionMinor = 0;
    params->nVersion.s.nRevision = 0;
    params->nVersion.s.nStep = 0;
}

SoftAMRNBEncoder::SoftAMRNBEncoder(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component)
    : SimpleSoftOMXComponent(name, callbacks, appData, component),
      mEncState(NULL),
      mSidState(NULL),
      mBitRate(0),
      mMode(MR475),
      mInputSize(0),
      mInputTimeUs(-1ll),
      mSawInputEOS(false),
      mSignalledError(false) {
    initPorts();
    CHECK_EQ(initEncoder(), (status_t)OK);
}

SoftAMRNBEncoder::~SoftAMRNBEncoder() {
    if (mEncState != NULL) {
        AMREncodeExit(&mEncState, &mSidState);
        mEncState = mSidState = NULL;
    }
}

void SoftAMRNBEncoder::initPorts() {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    InitOMXParams(&def);

    def.nPortIndex = 0;
    def.eDir = OMX_DirInput;
    def.nBufferCountMin = kNumBuffers;
    def.nBufferCountActual = def.nBufferCountMin;
    def.nBufferSize = kNumSamplesPerFrame * sizeof(int16_t);
    def.bEnabled = OMX_TRUE;
    def.bPopulated = OMX_FALSE;
    def.eDomain = OMX_PortDomainAudio;
    def.bBuffersContiguous = OMX_FALSE;
    def.nBufferAlignment = 1;

    def.format.audio.cMIMEType = const_cast<char *>("audio/raw");
    def.format.audio.pNativeRender = NULL;
    def.format.audio.bFlagErrorConcealment = OMX_FALSE;
    def.format.audio.eEncoding = OMX_AUDIO_CodingPCM;

    addPort(def);

    def.nPortIndex = 1;
    def.eDir = OMX_DirOutput;
    def.nBufferCountMin = kNumBuffers;
    def.nBufferCountActual = def.nBufferCountMin;
    def.nBufferSize = 8192;
    def.bEnabled = OMX_TRUE;
    def.bPopulated = OMX_FALSE;
    def.eDomain = OMX_PortDomainAudio;
    def.bBuffersContiguous = OMX_FALSE;
    def.nBufferAlignment = 2;

    def.format.audio.cMIMEType = const_cast<char *>("audio/3gpp");
    def.format.audio.pNativeRender = NULL;
    def.format.audio.bFlagErrorConcealment = OMX_FALSE;
    def.format.audio.eEncoding = OMX_AUDIO_CodingAMR;

    addPort(def);
}

status_t SoftAMRNBEncoder::initEncoder() {
    if (AMREncodeInit(&mEncState, &mSidState, false /* dtx_enable */) != 0) {
        return UNKNOWN_ERROR;
    }

    return OK;
}

OMX_ERRORTYPE SoftAMRNBEncoder::internalGetParameter(
        OMX_INDEXTYPE index, OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamAudioPortFormat:
        {
            OMX_AUDIO_PARAM_PORTFORMATTYPE *formatParams =
                (OMX_AUDIO_PARAM_PORTFORMATTYPE *)params;

            if (formatParams->nPortIndex > 1) {
                return OMX_ErrorUndefined;
            }

            if (formatParams->nIndex > 0) {
                return OMX_ErrorNoMore;
            }

            formatParams->eEncoding =
                (formatParams->nPortIndex == 0)
                    ? OMX_AUDIO_CodingPCM : OMX_AUDIO_CodingAMR;

            return OMX_ErrorNone;
        }

        case OMX_IndexParamAudioAmr:
        {
            OMX_AUDIO_PARAM_AMRTYPE *amrParams =
                (OMX_AUDIO_PARAM_AMRTYPE *)params;

            if (amrParams->nPortIndex != 1) {
                return OMX_ErrorUndefined;
            }

            amrParams->nChannels = 1;
            amrParams->nBitRate = mBitRate;
            amrParams->eAMRBandMode = (OMX_AUDIO_AMRBANDMODETYPE)(mMode + 1);
            amrParams->eAMRDTXMode = OMX_AUDIO_AMRDTXModeOff;
            amrParams->eAMRFrameFormat = OMX_AUDIO_AMRFrameFormatFSF;

            return OMX_ErrorNone;
        }

        case OMX_IndexParamAudioPcm:
        {
            OMX_AUDIO_PARAM_PCMMODETYPE *pcmParams =
                (OMX_AUDIO_PARAM_PCMMODETYPE *)params;

            if (pcmParams->nPortIndex != 0) {
                return OMX_ErrorUndefined;
            }

            pcmParams->eNumData = OMX_NumericalDataSigned;
            pcmParams->eEndian = OMX_EndianBig;
            pcmParams->bInterleaved = OMX_TRUE;
            pcmParams->nBitPerSample = 16;
            pcmParams->ePCMMode = OMX_AUDIO_PCMModeLinear;
            pcmParams->eChannelMapping[0] = OMX_AUDIO_ChannelCF;

            pcmParams->nChannels = 1;
            pcmParams->nSamplingRate = kSampleRate;

            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalGetParameter(index, params);
    }
}

OMX_ERRORTYPE SoftAMRNBEncoder::internalSetParameter(
        OMX_INDEXTYPE index, const OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamStandardComponentRole:
        {
            const OMX_PARAM_COMPONENTROLETYPE *roleParams =
                (const OMX_PARAM_COMPONENTROLETYPE *)params;

            if (strncmp((const char *)roleParams->cRole,
                        "audio_encoder.amrnb",
                        OMX_MAX_STRINGNAME_SIZE - 1)) {
                return OMX_ErrorUndefined;
            }

            return OMX_ErrorNone;
        }

        case OMX_IndexParamAudioPortFormat:
        {
            const OMX_AUDIO_PARAM_PORTFORMATTYPE *formatParams =
                (const OMX_AUDIO_PARAM_PORTFORMATTYPE *)params;

            if (formatParams->nPortIndex > 1) {
                return OMX_ErrorUndefined;
            }

            if (formatParams->nIndex > 0) {
                return OMX_ErrorNoMore;
            }

            if ((formatParams->nPortIndex == 0
                        && formatParams->eEncoding != OMX_AUDIO_CodingPCM)
                || (formatParams->nPortIndex == 1
                        && formatParams->eEncoding != OMX_AUDIO_CodingAMR)) {
                return OMX_ErrorUndefined;
            }

            return OMX_ErrorNone;
        }

        case OMX_IndexParamAudioAmr:
        {
            OMX_AUDIO_PARAM_AMRTYPE *amrParams =
                (OMX_AUDIO_PARAM_AMRTYPE *)params;

            if (amrParams->nPortIndex != 1) {
                return OMX_ErrorUndefined;
            }

            if (amrParams->nChannels != 1
                    || amrParams->eAMRDTXMode != OMX_AUDIO_AMRDTXModeOff
                    || amrParams->eAMRFrameFormat
                            != OMX_AUDIO_AMRFrameFormatFSF
                    || amrParams->eAMRBandMode < OMX_AUDIO_AMRBandModeNB0
                    || amrParams->eAMRBandMode > OMX_AUDIO_AMRBandModeNB7) {
                return OMX_ErrorUndefined;
            }

            mBitRate = amrParams->nBitRate;
            mMode = amrParams->eAMRBandMode - 1;

            amrParams->eAMRDTXMode = OMX_AUDIO_AMRDTXModeOff;
            amrParams->eAMRFrameFormat = OMX_AUDIO_AMRFrameFormatFSF;

            return OMX_ErrorNone;
        }

        case OMX_IndexParamAudioPcm:
        {
            OMX_AUDIO_PARAM_PCMMODETYPE *pcmParams =
                (OMX_AUDIO_PARAM_PCMMODETYPE *)params;

            if (pcmParams->nPortIndex != 0) {
                return OMX_ErrorUndefined;
            }

            if (pcmParams->nChannels != 1
                    || pcmParams->nSamplingRate != kSampleRate) {
                return OMX_ErrorUndefined;
            }

            return OMX_ErrorNone;
        }


        default:
            return SimpleSoftOMXComponent::internalSetParameter(index, params);
    }
}

void SoftAMRNBEncoder::onQueueFilled(OMX_U32 portIndex) {
    if (mSignalledError) {
        return;
    }

    List<BufferInfo *> &inQueue = getPortQueue(0);
    List<BufferInfo *> &outQueue = getPortQueue(1);

    size_t numBytesPerInputFrame = kNumSamplesPerFrame * sizeof(int16_t);

    for (;;) {
        // We do the following until we run out of buffers.

        while (mInputSize < numBytesPerInputFrame) {
            // As long as there's still input data to be read we
            // will drain "kNumSamplesPerFrame" samples
            // into the "mInputFrame" buffer and then encode those
            // as a unit into an output buffer.

            if (mSawInputEOS || inQueue.empty()) {
                return;
            }

            BufferInfo *inInfo = *inQueue.begin();
            OMX_BUFFERHEADERTYPE *inHeader = inInfo->mHeader;

            const void *inData = inHeader->pBuffer + inHeader->nOffset;

            size_t copy = numBytesPerInputFrame - mInputSize;
            if (copy > inHeader->nFilledLen) {
                copy = inHeader->nFilledLen;
            }

            if (mInputSize == 0) {
                mInputTimeUs = inHeader->nTimeStamp;
            }

            memcpy((uint8_t *)mInputFrame + mInputSize, inData, copy);
            mInputSize += copy;

            inHeader->nOffset += copy;
            inHeader->nFilledLen -= copy;

            // "Time" on the input buffer has in effect advanced by the
            // number of audio frames we just advanced nOffset by.
            inHeader->nTimeStamp +=
                (copy * 1000000ll / kSampleRate) / sizeof(int16_t);

            if (inHeader->nFilledLen == 0) {
                if (inHeader->nFlags & OMX_BUFFERFLAG_EOS) {
                    ALOGV("saw input EOS");
                    mSawInputEOS = true;

                    // Pad any remaining data with zeroes.
                    memset((uint8_t *)mInputFrame + mInputSize,
                           0,
                           numBytesPerInputFrame - mInputSize);

                    mInputSize = numBytesPerInputFrame;
                }

                inQueue.erase(inQueue.begin());
                inInfo->mOwnedByUs = false;
                notifyEmptyBufferDone(inHeader);

                inData = NULL;
                inHeader = NULL;
                inInfo = NULL;
            }
        }

        // At this  point we have all the input data necessary to encode
        // a single frame, all we need is an output buffer to store the result
        // in.

        if (outQueue.empty()) {
            return;
        }

        BufferInfo *outInfo = *outQueue.begin();
        OMX_BUFFERHEADERTYPE *outHeader = outInfo->mHeader;

        uint8_t *outPtr = outHeader->pBuffer + outHeader->nOffset;
        size_t outAvailable = outHeader->nAllocLen - outHeader->nOffset;

        Frame_Type_3GPP frameType;
        int res = AMREncode(
                mEncState, mSidState, (Mode)mMode,
                mInputFrame, outPtr, &frameType, AMR_TX_WMF);

        CHECK_GE(res, 0);
        CHECK_LE((size_t)res, outAvailable);

        // Convert header byte from WMF to IETF format.
        outPtr[0] = ((outPtr[0] << 3) | 4) & 0x7c;

        outHeader->nFilledLen = res;
        outHeader->nFlags = OMX_BUFFERFLAG_ENDOFFRAME;

        if (mSawInputEOS) {
            // We also tag this output buffer with EOS if it corresponds
            // to the final input buffer.
            outHeader->nFlags = OMX_BUFFERFLAG_EOS;
        }

        outHeader->nTimeStamp = mInputTimeUs;

#if 0
        ALOGI("sending %d bytes of data (time = %lld us, flags = 0x%08lx)",
              nOutputBytes, mInputTimeUs, outHeader->nFlags);

        hexdump(outHeader->pBuffer + outHeader->nOffset, outHeader->nFilledLen);
#endif

        outQueue.erase(outQueue.begin());
        outInfo->mOwnedByUs = false;
        notifyFillBufferDone(outHeader);

        outHeader = NULL;
        outInfo = NULL;

        mInputSize = 0;
    }
}

}  // namespace android

android::SoftOMXComponent *createSoftOMXComponent(
        const char *name, const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData, OMX_COMPONENTTYPE **component) {
    return new android::SoftAMRNBEncoder(name, callbacks, appData, component);
}
