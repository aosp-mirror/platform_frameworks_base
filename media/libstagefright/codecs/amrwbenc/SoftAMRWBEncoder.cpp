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
#define LOG_TAG "SoftAMRWBEncoder"
#include <utils/Log.h>

#include "SoftAMRWBEncoder.h"

#include "cmnMemory.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/hexdump.h>

namespace android {

static const int32_t kSampleRate = 16000;

template<class T>
static void InitOMXParams(T *params) {
    params->nSize = sizeof(T);
    params->nVersion.s.nVersionMajor = 1;
    params->nVersion.s.nVersionMinor = 0;
    params->nVersion.s.nRevision = 0;
    params->nVersion.s.nStep = 0;
}

SoftAMRWBEncoder::SoftAMRWBEncoder(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component)
    : SimpleSoftOMXComponent(name, callbacks, appData, component),
      mEncoderHandle(NULL),
      mApiHandle(NULL),
      mMemOperator(NULL),
      mBitRate(0),
      mMode(VOAMRWB_MD66),
      mInputSize(0),
      mInputTimeUs(-1ll),
      mSawInputEOS(false),
      mSignalledError(false) {
    initPorts();
    CHECK_EQ(initEncoder(), (status_t)OK);
}

SoftAMRWBEncoder::~SoftAMRWBEncoder() {
    if (mEncoderHandle != NULL) {
        CHECK_EQ(VO_ERR_NONE, mApiHandle->Uninit(mEncoderHandle));
        mEncoderHandle = NULL;
    }

    delete mApiHandle;
    mApiHandle = NULL;

    delete mMemOperator;
    mMemOperator = NULL;
}

void SoftAMRWBEncoder::initPorts() {
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

    def.format.audio.cMIMEType = const_cast<char *>("audio/amr-wb");
    def.format.audio.pNativeRender = NULL;
    def.format.audio.bFlagErrorConcealment = OMX_FALSE;
    def.format.audio.eEncoding = OMX_AUDIO_CodingAMR;

    addPort(def);
}

status_t SoftAMRWBEncoder::initEncoder() {
    mApiHandle = new VO_AUDIO_CODECAPI;

    if (VO_ERR_NONE != voGetAMRWBEncAPI(mApiHandle)) {
        ALOGE("Failed to get api handle");
        return UNKNOWN_ERROR;
    }

    mMemOperator = new VO_MEM_OPERATOR;
    mMemOperator->Alloc = cmnMemAlloc;
    mMemOperator->Copy = cmnMemCopy;
    mMemOperator->Free = cmnMemFree;
    mMemOperator->Set = cmnMemSet;
    mMemOperator->Check = cmnMemCheck;

    VO_CODEC_INIT_USERDATA userData;
    memset(&userData, 0, sizeof(userData));
    userData.memflag = VO_IMF_USERMEMOPERATOR;
    userData.memData = (VO_PTR) mMemOperator;

    if (VO_ERR_NONE != mApiHandle->Init(
                &mEncoderHandle, VO_AUDIO_CodingAMRWB, &userData)) {
        ALOGE("Failed to init AMRWB encoder");
        return UNKNOWN_ERROR;
    }

    VOAMRWBFRAMETYPE type = VOAMRWB_RFC3267;
    if (VO_ERR_NONE != mApiHandle->SetParam(
                mEncoderHandle, VO_PID_AMRWB_FRAMETYPE, &type)) {
        ALOGE("Failed to set AMRWB encoder frame type to %d", type);
        return UNKNOWN_ERROR;
    }

    return OK;
}

OMX_ERRORTYPE SoftAMRWBEncoder::internalGetParameter(
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

            amrParams->eAMRBandMode =
                (OMX_AUDIO_AMRBANDMODETYPE)(mMode + OMX_AUDIO_AMRBandModeWB0);

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

OMX_ERRORTYPE SoftAMRWBEncoder::internalSetParameter(
        OMX_INDEXTYPE index, const OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamStandardComponentRole:
        {
            const OMX_PARAM_COMPONENTROLETYPE *roleParams =
                (const OMX_PARAM_COMPONENTROLETYPE *)params;

            if (strncmp((const char *)roleParams->cRole,
                        "audio_encoder.amrwb",
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
                    || amrParams->eAMRBandMode < OMX_AUDIO_AMRBandModeWB0
                    || amrParams->eAMRBandMode > OMX_AUDIO_AMRBandModeWB8) {
                return OMX_ErrorUndefined;
            }

            mBitRate = amrParams->nBitRate;

            mMode = (VOAMRWBMODE)(
                    amrParams->eAMRBandMode - OMX_AUDIO_AMRBandModeWB0);

            amrParams->eAMRDTXMode = OMX_AUDIO_AMRDTXModeOff;
            amrParams->eAMRFrameFormat = OMX_AUDIO_AMRFrameFormatFSF;

            if (VO_ERR_NONE !=
                    mApiHandle->SetParam(
                        mEncoderHandle, VO_PID_AMRWB_MODE,  &mMode)) {
                ALOGE("Failed to set AMRWB encoder mode to %d", mMode);
                return OMX_ErrorUndefined;
            }

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
                    || pcmParams->nSamplingRate != (OMX_U32)kSampleRate) {
                return OMX_ErrorUndefined;
            }

            return OMX_ErrorNone;
        }


        default:
            return SimpleSoftOMXComponent::internalSetParameter(index, params);
    }
}

void SoftAMRWBEncoder::onQueueFilled(OMX_U32 portIndex) {
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

        VO_CODECBUFFER inputData;
        memset(&inputData, 0, sizeof(inputData));
        inputData.Buffer = (unsigned char *) mInputFrame;
        inputData.Length = mInputSize;

        CHECK_EQ(VO_ERR_NONE,
                 mApiHandle->SetInputData(mEncoderHandle, &inputData));

        VO_CODECBUFFER outputData;
        memset(&outputData, 0, sizeof(outputData));
        VO_AUDIO_OUTPUTINFO outputInfo;
        memset(&outputInfo, 0, sizeof(outputInfo));

        outputData.Buffer = outPtr;
        outputData.Length = outAvailable;
        VO_U32 ret = mApiHandle->GetOutputData(
                mEncoderHandle, &outputData, &outputInfo);
        CHECK(ret == VO_ERR_NONE || ret == VO_ERR_INPUT_BUFFER_SMALL);

        outHeader->nFilledLen = outputData.Length;
        outHeader->nFlags = OMX_BUFFERFLAG_ENDOFFRAME;

        if (mSawInputEOS) {
            // We also tag this output buffer with EOS if it corresponds
            // to the final input buffer.
            outHeader->nFlags = OMX_BUFFERFLAG_EOS;
        }

        outHeader->nTimeStamp = mInputTimeUs;

#if 0
        ALOGI("sending %ld bytes of data (time = %lld us, flags = 0x%08lx)",
              outHeader->nFilledLen, mInputTimeUs, outHeader->nFlags);

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
    return new android::SoftAMRWBEncoder(name, callbacks, appData, component);
}
