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
#define LOG_TAG "SoftAACEncoder"
#include <utils/Log.h>

#include "SoftAACEncoder.h"

#include "voAAC.h"
#include "cmnMemory.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/hexdump.h>

namespace android {

template<class T>
static void InitOMXParams(T *params) {
    params->nSize = sizeof(T);
    params->nVersion.s.nVersionMajor = 1;
    params->nVersion.s.nVersionMinor = 0;
    params->nVersion.s.nRevision = 0;
    params->nVersion.s.nStep = 0;
}

SoftAACEncoder::SoftAACEncoder(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component)
    : SimpleSoftOMXComponent(name, callbacks, appData, component),
      mEncoderHandle(NULL),
      mApiHandle(NULL),
      mMemOperator(NULL),
      mNumChannels(1),
      mSampleRate(44100),
      mBitRate(0),
      mSentCodecSpecificData(false),
      mInputSize(0),
      mInputFrame(NULL),
      mInputTimeUs(-1ll),
      mSawInputEOS(false),
      mSignalledError(false) {
    initPorts();
    CHECK_EQ(initEncoder(), (status_t)OK);

    setAudioParams();
}

SoftAACEncoder::~SoftAACEncoder() {
    delete[] mInputFrame;
    mInputFrame = NULL;

    if (mEncoderHandle) {
        CHECK_EQ(VO_ERR_NONE, mApiHandle->Uninit(mEncoderHandle));
        mEncoderHandle = NULL;
    }

    delete mApiHandle;
    mApiHandle = NULL;

    delete mMemOperator;
    mMemOperator = NULL;
}

void SoftAACEncoder::initPorts() {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    InitOMXParams(&def);

    def.nPortIndex = 0;
    def.eDir = OMX_DirInput;
    def.nBufferCountMin = kNumBuffers;
    def.nBufferCountActual = def.nBufferCountMin;
    def.nBufferSize = kNumSamplesPerFrame * sizeof(int16_t) * 2;
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

    def.format.audio.cMIMEType = const_cast<char *>("audio/aac");
    def.format.audio.pNativeRender = NULL;
    def.format.audio.bFlagErrorConcealment = OMX_FALSE;
    def.format.audio.eEncoding = OMX_AUDIO_CodingAAC;

    addPort(def);
}

status_t SoftAACEncoder::initEncoder() {
    mApiHandle = new VO_AUDIO_CODECAPI;

    if (VO_ERR_NONE != voGetAACEncAPI(mApiHandle)) {
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
    if (VO_ERR_NONE !=
            mApiHandle->Init(&mEncoderHandle, VO_AUDIO_CodingAAC, &userData)) {
        ALOGE("Failed to init AAC encoder");
        return UNKNOWN_ERROR;
    }

    return OK;
}

OMX_ERRORTYPE SoftAACEncoder::internalGetParameter(
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
                    ? OMX_AUDIO_CodingPCM : OMX_AUDIO_CodingAAC;

            return OMX_ErrorNone;
        }

        case OMX_IndexParamAudioAac:
        {
            OMX_AUDIO_PARAM_AACPROFILETYPE *aacParams =
                (OMX_AUDIO_PARAM_AACPROFILETYPE *)params;

            if (aacParams->nPortIndex != 1) {
                return OMX_ErrorUndefined;
            }

            aacParams->nBitRate = mBitRate;
            aacParams->nAudioBandWidth = 0;
            aacParams->nAACtools = 0;
            aacParams->nAACERtools = 0;
            aacParams->eAACProfile = OMX_AUDIO_AACObjectMain;
            aacParams->eAACStreamFormat = OMX_AUDIO_AACStreamFormatMP4FF;
            aacParams->eChannelMode = OMX_AUDIO_ChannelModeStereo;

            aacParams->nChannels = mNumChannels;
            aacParams->nSampleRate = mSampleRate;
            aacParams->nFrameLength = 0;

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
            pcmParams->eChannelMapping[0] = OMX_AUDIO_ChannelLF;
            pcmParams->eChannelMapping[1] = OMX_AUDIO_ChannelRF;

            pcmParams->nChannels = mNumChannels;
            pcmParams->nSamplingRate = mSampleRate;

            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalGetParameter(index, params);
    }
}

OMX_ERRORTYPE SoftAACEncoder::internalSetParameter(
        OMX_INDEXTYPE index, const OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamStandardComponentRole:
        {
            const OMX_PARAM_COMPONENTROLETYPE *roleParams =
                (const OMX_PARAM_COMPONENTROLETYPE *)params;

            if (strncmp((const char *)roleParams->cRole,
                        "audio_encoder.aac",
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
                        && formatParams->eEncoding != OMX_AUDIO_CodingAAC)) {
                return OMX_ErrorUndefined;
            }

            return OMX_ErrorNone;
        }

        case OMX_IndexParamAudioAac:
        {
            OMX_AUDIO_PARAM_AACPROFILETYPE *aacParams =
                (OMX_AUDIO_PARAM_AACPROFILETYPE *)params;

            if (aacParams->nPortIndex != 1) {
                return OMX_ErrorUndefined;
            }

            mBitRate = aacParams->nBitRate;
            mNumChannels = aacParams->nChannels;
            mSampleRate = aacParams->nSampleRate;

            if (setAudioParams() != OK) {
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

            mNumChannels = pcmParams->nChannels;
            mSampleRate = pcmParams->nSamplingRate;

            if (setAudioParams() != OK) {
                return OMX_ErrorUndefined;
            }

            return OMX_ErrorNone;
        }


        default:
            return SimpleSoftOMXComponent::internalSetParameter(index, params);
    }
}

status_t SoftAACEncoder::setAudioParams() {
    // We call this whenever sample rate, number of channels or bitrate change
    // in reponse to setParameter calls.

    ALOGV("setAudioParams: %lu Hz, %lu channels, %lu bps",
         mSampleRate, mNumChannels, mBitRate);

    status_t err = setAudioSpecificConfigData();

    if (err != OK) {
        return err;
    }

    AACENC_PARAM params;
    memset(&params, 0, sizeof(params));
    params.sampleRate = mSampleRate;
    params.bitRate = mBitRate;
    params.nChannels = mNumChannels;
    params.adtsUsed = 0;  // We add adts header in the file writer if needed.
    if (VO_ERR_NONE != mApiHandle->SetParam(
                mEncoderHandle, VO_PID_AAC_ENCPARAM,  &params)) {
        ALOGE("Failed to set AAC encoder parameters");
        return UNKNOWN_ERROR;
    }

    return OK;
}

static status_t getSampleRateTableIndex(int32_t sampleRate, int32_t &index) {
    static const int32_t kSampleRateTable[] = {
        96000, 88200, 64000, 48000, 44100, 32000,
        24000, 22050, 16000, 12000, 11025, 8000
    };
    const int32_t tableSize =
        sizeof(kSampleRateTable) / sizeof(kSampleRateTable[0]);

    for (int32_t i = 0; i < tableSize; ++i) {
        if (sampleRate == kSampleRateTable[i]) {
            index = i;
            return OK;
        }
    }

    return UNKNOWN_ERROR;
}

status_t SoftAACEncoder::setAudioSpecificConfigData() {
    // The AAC encoder's audio specific config really only encodes
    // number of channels and the sample rate (mapped to an index into
    // a fixed sample rate table).

    int32_t index;
    status_t err = getSampleRateTableIndex(mSampleRate, index);
    if (err != OK) {
        ALOGE("Unsupported sample rate (%lu Hz)", mSampleRate);
        return err;
    }

    if (mNumChannels > 2 || mNumChannels <= 0) {
        ALOGE("Unsupported number of channels(%lu)", mNumChannels);
        return UNKNOWN_ERROR;
    }

    // OMX_AUDIO_AACObjectLC
    mAudioSpecificConfigData[0] = ((0x02 << 3) | (index >> 1));
    mAudioSpecificConfigData[1] = ((index & 0x01) << 7) | (mNumChannels << 3);

    return OK;
}

void SoftAACEncoder::onQueueFilled(OMX_U32 portIndex) {
    if (mSignalledError) {
        return;
    }

    List<BufferInfo *> &inQueue = getPortQueue(0);
    List<BufferInfo *> &outQueue = getPortQueue(1);

    if (!mSentCodecSpecificData) {
        // The very first thing we want to output is the codec specific
        // data. It does not require any input data but we will need an
        // output buffer to store it in.

        if (outQueue.empty()) {
            return;
        }

        BufferInfo *outInfo = *outQueue.begin();
        OMX_BUFFERHEADERTYPE *outHeader = outInfo->mHeader;
        outHeader->nFilledLen = sizeof(mAudioSpecificConfigData);
        outHeader->nFlags = OMX_BUFFERFLAG_CODECCONFIG;

        uint8_t *out = outHeader->pBuffer + outHeader->nOffset;
        memcpy(out, mAudioSpecificConfigData, sizeof(mAudioSpecificConfigData));

#if 0
        ALOGI("sending codec specific data.");
        hexdump(out, sizeof(mAudioSpecificConfigData));
#endif

        outQueue.erase(outQueue.begin());
        outInfo->mOwnedByUs = false;
        notifyFillBufferDone(outHeader);

        mSentCodecSpecificData = true;
    }

    size_t numBytesPerInputFrame =
        mNumChannels * kNumSamplesPerFrame * sizeof(int16_t);

    for (;;) {
        // We do the following until we run out of buffers.

        while (mInputSize < numBytesPerInputFrame) {
            // As long as there's still input data to be read we
            // will drain "kNumSamplesPerFrame * mNumChannels" samples
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

            if (mInputFrame == NULL) {
                mInputFrame = new int16_t[kNumSamplesPerFrame * mNumChannels];
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
                (copy * 1000000ll / mSampleRate)
                    / (mNumChannels * sizeof(int16_t));

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

        VO_CODECBUFFER inputData;
        memset(&inputData, 0, sizeof(inputData));
        inputData.Buffer = (unsigned char *)mInputFrame;
        inputData.Length = numBytesPerInputFrame;
        CHECK(VO_ERR_NONE ==
                mApiHandle->SetInputData(mEncoderHandle, &inputData));

        VO_CODECBUFFER outputData;
        memset(&outputData, 0, sizeof(outputData));
        VO_AUDIO_OUTPUTINFO outputInfo;
        memset(&outputInfo, 0, sizeof(outputInfo));

        uint8_t *outPtr = (uint8_t *)outHeader->pBuffer + outHeader->nOffset;
        size_t outAvailable = outHeader->nAllocLen - outHeader->nOffset;

        VO_U32 ret = VO_ERR_NONE;
        size_t nOutputBytes = 0;
        do {
            outputData.Buffer = outPtr;
            outputData.Length = outAvailable - nOutputBytes;
            ret = mApiHandle->GetOutputData(
                    mEncoderHandle, &outputData, &outputInfo);
            if (ret == VO_ERR_NONE) {
                outPtr += outputData.Length;
                nOutputBytes += outputData.Length;
            }
        } while (ret != VO_ERR_INPUT_BUFFER_SMALL);

        outHeader->nFilledLen = nOutputBytes;

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
    return new android::SoftAACEncoder(name, callbacks, appData, component);
}
