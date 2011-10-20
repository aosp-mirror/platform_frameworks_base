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
#define LOG_TAG "SoftVorbis"
#include <utils/Log.h>

#include "SoftVorbis.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaDefs.h>

extern "C" {
    #include <Tremolo/codec_internal.h>

    int _vorbis_unpack_books(vorbis_info *vi,oggpack_buffer *opb);
    int _vorbis_unpack_info(vorbis_info *vi,oggpack_buffer *opb);
    int _vorbis_unpack_comment(vorbis_comment *vc,oggpack_buffer *opb);
}

namespace android {

template<class T>
static void InitOMXParams(T *params) {
    params->nSize = sizeof(T);
    params->nVersion.s.nVersionMajor = 1;
    params->nVersion.s.nVersionMinor = 0;
    params->nVersion.s.nRevision = 0;
    params->nVersion.s.nStep = 0;
}

SoftVorbis::SoftVorbis(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component)
    : SimpleSoftOMXComponent(name, callbacks, appData, component),
      mInputBufferCount(0),
      mState(NULL),
      mVi(NULL),
      mAnchorTimeUs(0),
      mNumFramesOutput(0),
      mNumFramesLeftOnPage(-1),
      mOutputPortSettingsChange(NONE) {
    initPorts();
    CHECK_EQ(initDecoder(), (status_t)OK);
}

SoftVorbis::~SoftVorbis() {
    if (mState != NULL) {
        vorbis_dsp_clear(mState);
        delete mState;
        mState = NULL;
    }

    if (mVi != NULL) {
        vorbis_info_clear(mVi);
        delete mVi;
        mVi = NULL;
    }
}

void SoftVorbis::initPorts() {
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
        const_cast<char *>(MEDIA_MIMETYPE_AUDIO_VORBIS);

    def.format.audio.pNativeRender = NULL;
    def.format.audio.bFlagErrorConcealment = OMX_FALSE;
    def.format.audio.eEncoding = OMX_AUDIO_CodingAAC;

    addPort(def);

    def.nPortIndex = 1;
    def.eDir = OMX_DirOutput;
    def.nBufferCountMin = kNumBuffers;
    def.nBufferCountActual = def.nBufferCountMin;
    def.nBufferSize = kMaxNumSamplesPerBuffer * sizeof(int16_t);
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

status_t SoftVorbis::initDecoder() {
    return OK;
}

OMX_ERRORTYPE SoftVorbis::internalGetParameter(
        OMX_INDEXTYPE index, OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamAudioVorbis:
        {
            OMX_AUDIO_PARAM_VORBISTYPE *vorbisParams =
                (OMX_AUDIO_PARAM_VORBISTYPE *)params;

            if (vorbisParams->nPortIndex != 0) {
                return OMX_ErrorUndefined;
            }

            vorbisParams->nBitRate = 0;
            vorbisParams->nMinBitRate = 0;
            vorbisParams->nMaxBitRate = 0;
            vorbisParams->nAudioBandWidth = 0;
            vorbisParams->nQuality = 3;
            vorbisParams->bManaged = OMX_FALSE;
            vorbisParams->bDownmix = OMX_FALSE;

            if (!isConfigured()) {
                vorbisParams->nChannels = 1;
                vorbisParams->nSampleRate = 44100;
            } else {
                vorbisParams->nChannels = mVi->channels;
                vorbisParams->nSampleRate = mVi->rate;
                vorbisParams->nBitRate = mVi->bitrate_nominal;
                vorbisParams->nMinBitRate = mVi->bitrate_lower;
                vorbisParams->nMaxBitRate = mVi->bitrate_upper;
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

            pcmParams->eNumData = OMX_NumericalDataSigned;
            pcmParams->eEndian = OMX_EndianBig;
            pcmParams->bInterleaved = OMX_TRUE;
            pcmParams->nBitPerSample = 16;
            pcmParams->ePCMMode = OMX_AUDIO_PCMModeLinear;
            pcmParams->eChannelMapping[0] = OMX_AUDIO_ChannelLF;
            pcmParams->eChannelMapping[1] = OMX_AUDIO_ChannelRF;

            if (!isConfigured()) {
                pcmParams->nChannels = 1;
                pcmParams->nSamplingRate = 44100;
            } else {
                pcmParams->nChannels = mVi->channels;
                pcmParams->nSamplingRate = mVi->rate;
            }

            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalGetParameter(index, params);
    }
}

OMX_ERRORTYPE SoftVorbis::internalSetParameter(
        OMX_INDEXTYPE index, const OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamStandardComponentRole:
        {
            const OMX_PARAM_COMPONENTROLETYPE *roleParams =
                (const OMX_PARAM_COMPONENTROLETYPE *)params;

            if (strncmp((const char *)roleParams->cRole,
                        "audio_decoder.vorbis",
                        OMX_MAX_STRINGNAME_SIZE - 1)) {
                return OMX_ErrorUndefined;
            }

            return OMX_ErrorNone;
        }

        case OMX_IndexParamAudioVorbis:
        {
            const OMX_AUDIO_PARAM_VORBISTYPE *vorbisParams =
                (const OMX_AUDIO_PARAM_VORBISTYPE *)params;

            if (vorbisParams->nPortIndex != 0) {
                return OMX_ErrorUndefined;
            }

            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalSetParameter(index, params);
    }
}

bool SoftVorbis::isConfigured() const {
    return mInputBufferCount >= 2;
}

static void makeBitReader(
        const void *data, size_t size,
        ogg_buffer *buf, ogg_reference *ref, oggpack_buffer *bits) {
    buf->data = (uint8_t *)data;
    buf->size = size;
    buf->refcount = 1;
    buf->ptr.owner = NULL;

    ref->buffer = buf;
    ref->begin = 0;
    ref->length = size;
    ref->next = NULL;

    oggpack_readinit(bits, ref);
}

void SoftVorbis::onQueueFilled(OMX_U32 portIndex) {
    List<BufferInfo *> &inQueue = getPortQueue(0);
    List<BufferInfo *> &outQueue = getPortQueue(1);

    if (mOutputPortSettingsChange != NONE) {
        return;
    }

    if (portIndex == 0 && mInputBufferCount < 2) {
        BufferInfo *info = *inQueue.begin();
        OMX_BUFFERHEADERTYPE *header = info->mHeader;

        const uint8_t *data = header->pBuffer + header->nOffset;
        size_t size = header->nFilledLen;

        ogg_buffer buf;
        ogg_reference ref;
        oggpack_buffer bits;

        makeBitReader(
                (const uint8_t *)data + 7, size - 7,
                &buf, &ref, &bits);

        if (mInputBufferCount == 0) {
            CHECK(mVi == NULL);
            mVi = new vorbis_info;
            vorbis_info_init(mVi);

            CHECK_EQ(0, _vorbis_unpack_info(mVi, &bits));
        } else {
            CHECK_EQ(0, _vorbis_unpack_books(mVi, &bits));

            CHECK(mState == NULL);
            mState = new vorbis_dsp_state;
            CHECK_EQ(0, vorbis_dsp_init(mState, mVi));

            notify(OMX_EventPortSettingsChanged, 1, 0, NULL);
            mOutputPortSettingsChange = AWAITING_DISABLED;
        }

        inQueue.erase(inQueue.begin());
        info->mOwnedByUs = false;
        notifyEmptyBufferDone(header);

        ++mInputBufferCount;

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

        int32_t numPageSamples;
        CHECK_GE(inHeader->nFilledLen, sizeof(numPageSamples));
        memcpy(&numPageSamples,
               inHeader->pBuffer
                + inHeader->nOffset + inHeader->nFilledLen - 4,
               sizeof(numPageSamples));

        if (numPageSamples >= 0) {
            mNumFramesLeftOnPage = numPageSamples;
        }

        if (inHeader->nOffset == 0) {
            mAnchorTimeUs = inHeader->nTimeStamp;
            mNumFramesOutput = 0;
        }

        inHeader->nFilledLen -= sizeof(numPageSamples);;

        ogg_buffer buf;
        buf.data = inHeader->pBuffer + inHeader->nOffset;
        buf.size = inHeader->nFilledLen;
        buf.refcount = 1;
        buf.ptr.owner = NULL;

        ogg_reference ref;
        ref.buffer = &buf;
        ref.begin = 0;
        ref.length = buf.size;
        ref.next = NULL;

        ogg_packet pack;
        pack.packet = &ref;
        pack.bytes = ref.length;
        pack.b_o_s = 0;
        pack.e_o_s = 0;
        pack.granulepos = 0;
        pack.packetno = 0;

        int numFrames = 0;

        int err = vorbis_dsp_synthesis(mState, &pack, 1);
        if (err != 0) {
            LOGW("vorbis_dsp_synthesis returned %d", err);
        } else {
            numFrames = vorbis_dsp_pcmout(
                    mState, (int16_t *)outHeader->pBuffer,
                    kMaxNumSamplesPerBuffer);

            if (numFrames < 0) {
                LOGE("vorbis_dsp_pcmout returned %d", numFrames);
                numFrames = 0;
            }
        }

        if (mNumFramesLeftOnPage >= 0) {
            if (numFrames > mNumFramesLeftOnPage) {
                ALOGV("discarding %d frames at end of page",
                     numFrames - mNumFramesLeftOnPage);
                numFrames = mNumFramesLeftOnPage;
            }
            mNumFramesLeftOnPage -= numFrames;
        }

        outHeader->nFilledLen = numFrames * sizeof(int16_t) * mVi->channels;
        outHeader->nOffset = 0;
        outHeader->nFlags = 0;

        outHeader->nTimeStamp =
            mAnchorTimeUs
                + (mNumFramesOutput * 1000000ll) / mVi->rate;

        mNumFramesOutput += numFrames;

        inInfo->mOwnedByUs = false;
        inQueue.erase(inQueue.begin());
        inInfo = NULL;
        notifyEmptyBufferDone(inHeader);
        inHeader = NULL;

        outInfo->mOwnedByUs = false;
        outQueue.erase(outQueue.begin());
        outInfo = NULL;
        notifyFillBufferDone(outHeader);
        outHeader = NULL;

        ++mInputBufferCount;
    }
}

void SoftVorbis::onPortFlushCompleted(OMX_U32 portIndex) {
    if (portIndex == 0 && mState != NULL) {
        // Make sure that the next buffer output does not still
        // depend on fragments from the last one decoded.

        mNumFramesOutput = 0;
        vorbis_dsp_restart(mState);
    }
}

void SoftVorbis::onPortEnableCompleted(OMX_U32 portIndex, bool enabled) {
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
    return new android::SoftVorbis(name, callbacks, appData, component);
}
