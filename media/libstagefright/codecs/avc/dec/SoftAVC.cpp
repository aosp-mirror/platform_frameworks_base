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
#define LOG_TAG "SoftAVC"
#include <utils/Log.h>

#include "SoftAVC.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/IOMX.h>

#include "avcdec_api.h"
#include "avcdec_int.h"

namespace android {

static const char kStartCode[4] = { 0x00, 0x00, 0x00, 0x01 };

static const CodecProfileLevel kProfileLevels[] = {
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel1 },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel1b },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel11 },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel12 },
};

template<class T>
static void InitOMXParams(T *params) {
    params->nSize = sizeof(T);
    params->nVersion.s.nVersionMajor = 1;
    params->nVersion.s.nVersionMinor = 0;
    params->nVersion.s.nRevision = 0;
    params->nVersion.s.nStep = 0;
}

static int32_t Malloc(void *userData, int32_t size, int32_t attrs) {
    return reinterpret_cast<int32_t>(malloc(size));
}

static void Free(void *userData, int32_t ptr) {
    free(reinterpret_cast<void *>(ptr));
}

SoftAVC::SoftAVC(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component)
    : SimpleSoftOMXComponent(name, callbacks, appData, component),
      mHandle(new tagAVCHandle),
      mInputBufferCount(0),
      mWidth(160),
      mHeight(120),
      mCropLeft(0),
      mCropTop(0),
      mCropRight(mWidth - 1),
      mCropBottom(mHeight - 1),
      mSPSSeen(false),
      mPPSSeen(false),
      mCurrentTimeUs(-1),
      mEOSStatus(INPUT_DATA_AVAILABLE),
      mOutputPortSettingsChange(NONE) {
    initPorts();
    CHECK_EQ(initDecoder(), (status_t)OK);
}

SoftAVC::~SoftAVC() {
    PVAVCCleanUpDecoder(mHandle);

    delete mHandle;
    mHandle = NULL;
}

void SoftAVC::initPorts() {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    InitOMXParams(&def);

    def.nPortIndex = 0;
    def.eDir = OMX_DirInput;
    def.nBufferCountMin = kNumInputBuffers;
    def.nBufferCountActual = def.nBufferCountMin;
    def.nBufferSize = 8192;
    def.bEnabled = OMX_TRUE;
    def.bPopulated = OMX_FALSE;
    def.eDomain = OMX_PortDomainVideo;
    def.bBuffersContiguous = OMX_FALSE;
    def.nBufferAlignment = 1;

    def.format.video.cMIMEType = const_cast<char *>(MEDIA_MIMETYPE_VIDEO_AVC);
    def.format.video.pNativeRender = NULL;
    def.format.video.nFrameWidth = mWidth;
    def.format.video.nFrameHeight = mHeight;
    def.format.video.nStride = def.format.video.nFrameWidth;
    def.format.video.nSliceHeight = def.format.video.nFrameHeight;
    def.format.video.nBitrate = 0;
    def.format.video.xFramerate = 0;
    def.format.video.bFlagErrorConcealment = OMX_FALSE;
    def.format.video.eCompressionFormat = OMX_VIDEO_CodingAVC;
    def.format.video.eColorFormat = OMX_COLOR_FormatUnused;
    def.format.video.pNativeWindow = NULL;

    addPort(def);

    def.nPortIndex = 1;
    def.eDir = OMX_DirOutput;
    def.nBufferCountMin = kNumOutputBuffers;
    def.nBufferCountActual = def.nBufferCountMin;
    def.bEnabled = OMX_TRUE;
    def.bPopulated = OMX_FALSE;
    def.eDomain = OMX_PortDomainVideo;
    def.bBuffersContiguous = OMX_FALSE;
    def.nBufferAlignment = 2;

    def.format.video.cMIMEType = const_cast<char *>(MEDIA_MIMETYPE_VIDEO_RAW);
    def.format.video.pNativeRender = NULL;
    def.format.video.nFrameWidth = mWidth;
    def.format.video.nFrameHeight = mHeight;
    def.format.video.nStride = def.format.video.nFrameWidth;
    def.format.video.nSliceHeight = def.format.video.nFrameHeight;
    def.format.video.nBitrate = 0;
    def.format.video.xFramerate = 0;
    def.format.video.bFlagErrorConcealment = OMX_FALSE;
    def.format.video.eCompressionFormat = OMX_VIDEO_CodingUnused;
    def.format.video.eColorFormat = OMX_COLOR_FormatYUV420Planar;
    def.format.video.pNativeWindow = NULL;

    def.nBufferSize =
        (def.format.video.nFrameWidth * def.format.video.nFrameHeight * 3) / 2;

    addPort(def);
}

status_t SoftAVC::initDecoder() {
    memset(mHandle, 0, sizeof(tagAVCHandle));
    mHandle->AVCObject = NULL;
    mHandle->userData = this;
    mHandle->CBAVC_DPBAlloc = ActivateSPSWrapper;
    mHandle->CBAVC_FrameBind = BindFrameWrapper;
    mHandle->CBAVC_FrameUnbind = UnbindFrame;
    mHandle->CBAVC_Malloc = Malloc;
    mHandle->CBAVC_Free = Free;

    return OK;
}

OMX_ERRORTYPE SoftAVC::internalGetParameter(
        OMX_INDEXTYPE index, OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamVideoPortFormat:
        {
            OMX_VIDEO_PARAM_PORTFORMATTYPE *formatParams =
                (OMX_VIDEO_PARAM_PORTFORMATTYPE *)params;

            if (formatParams->nPortIndex > 1) {
                return OMX_ErrorUndefined;
            }

            if (formatParams->nIndex != 0) {
                return OMX_ErrorNoMore;
            }

            if (formatParams->nPortIndex == 0) {
                formatParams->eCompressionFormat = OMX_VIDEO_CodingAVC;
                formatParams->eColorFormat = OMX_COLOR_FormatUnused;
                formatParams->xFramerate = 0;
            } else {
                CHECK_EQ(formatParams->nPortIndex, 1u);

                formatParams->eCompressionFormat = OMX_VIDEO_CodingUnused;
                formatParams->eColorFormat = OMX_COLOR_FormatYUV420Planar;
                formatParams->xFramerate = 0;
            }

            return OMX_ErrorNone;
        }

        case OMX_IndexParamVideoProfileLevelQuerySupported:
        {
            OMX_VIDEO_PARAM_PROFILELEVELTYPE *profileLevel =
                    (OMX_VIDEO_PARAM_PROFILELEVELTYPE *) params;

            if (profileLevel->nPortIndex != 0) {  // Input port only
                LOGE("Invalid port index: %ld", profileLevel->nPortIndex);
                return OMX_ErrorUnsupportedIndex;
            }

            size_t index = profileLevel->nProfileIndex;
            size_t nProfileLevels =
                    sizeof(kProfileLevels) / sizeof(kProfileLevels[0]);
            if (index >= nProfileLevels) {
                return OMX_ErrorNoMore;
            }

            profileLevel->eProfile = kProfileLevels[index].mProfile;
            profileLevel->eLevel = kProfileLevels[index].mLevel;
            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalGetParameter(index, params);
    }
}

OMX_ERRORTYPE SoftAVC::internalSetParameter(
        OMX_INDEXTYPE index, const OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamStandardComponentRole:
        {
            const OMX_PARAM_COMPONENTROLETYPE *roleParams =
                (const OMX_PARAM_COMPONENTROLETYPE *)params;

            if (strncmp((const char *)roleParams->cRole,
                        "video_decoder.avc",
                        OMX_MAX_STRINGNAME_SIZE - 1)) {
                return OMX_ErrorUndefined;
            }

            return OMX_ErrorNone;
        }

        case OMX_IndexParamVideoPortFormat:
        {
            OMX_VIDEO_PARAM_PORTFORMATTYPE *formatParams =
                (OMX_VIDEO_PARAM_PORTFORMATTYPE *)params;

            if (formatParams->nPortIndex > 1) {
                return OMX_ErrorUndefined;
            }

            if (formatParams->nIndex != 0) {
                return OMX_ErrorNoMore;
            }

            return OMX_ErrorNone;
        }

        default:
            return SimpleSoftOMXComponent::internalSetParameter(index, params);
    }
}

OMX_ERRORTYPE SoftAVC::getConfig(
        OMX_INDEXTYPE index, OMX_PTR params) {
    switch (index) {
        case OMX_IndexConfigCommonOutputCrop:
        {
            OMX_CONFIG_RECTTYPE *rectParams = (OMX_CONFIG_RECTTYPE *)params;

            if (rectParams->nPortIndex != 1) {
                return OMX_ErrorUndefined;
            }

            rectParams->nLeft = mCropLeft;
            rectParams->nTop = mCropTop;
            rectParams->nWidth = mCropRight - mCropLeft + 1;
            rectParams->nHeight = mCropBottom - mCropTop + 1;

            return OMX_ErrorNone;
        }

        default:
            return OMX_ErrorUnsupportedIndex;
    }
}

static void findNALFragment(
        const OMX_BUFFERHEADERTYPE *inHeader,
        const uint8_t **fragPtr, size_t *fragSize) {
    const uint8_t *data = inHeader->pBuffer + inHeader->nOffset;

    size_t size = inHeader->nFilledLen;

    CHECK(size >= 4);
    CHECK(!memcmp(kStartCode, data, 4));

    size_t offset = 4;
    while (offset + 3 < size && memcmp(kStartCode, &data[offset], 4)) {
        ++offset;
    }

    *fragPtr = &data[4];
    if (offset + 3 >= size) {
        *fragSize = size - 4;
    } else {
        *fragSize = offset - 4;
    }
}

void SoftAVC::onQueueFilled(OMX_U32 portIndex) {
    if (mOutputPortSettingsChange != NONE) {
        return;
    }

    List<BufferInfo *> &inQueue = getPortQueue(0);
    List<BufferInfo *> &outQueue = getPortQueue(1);

    if (mEOSStatus == OUTPUT_FRAMES_FLUSHED) {
        return;
    }

    while ((mEOSStatus != INPUT_DATA_AVAILABLE || !inQueue.empty())
            && outQueue.size() == kNumOutputBuffers) {
        if (mEOSStatus == INPUT_EOS_SEEN) {
            OMX_BUFFERHEADERTYPE *outHeader;
            if (drainOutputBuffer(&outHeader)) {
                List<BufferInfo *>::iterator it = outQueue.begin();
                while ((*it)->mHeader != outHeader) {
                    ++it;
                }

                BufferInfo *outInfo = *it;
                outInfo->mOwnedByUs = false;
                outQueue.erase(it);
                outInfo = NULL;

                notifyFillBufferDone(outHeader);
                outHeader = NULL;
                return;
            }

            BufferInfo *outInfo = *outQueue.begin();
            outHeader = outInfo->mHeader;

            outHeader->nOffset = 0;
            outHeader->nFilledLen = 0;
            outHeader->nFlags = OMX_BUFFERFLAG_EOS;
            outHeader->nTimeStamp = 0;

            outQueue.erase(outQueue.begin());
            outInfo->mOwnedByUs = false;
            notifyFillBufferDone(outHeader);

            mEOSStatus = OUTPUT_FRAMES_FLUSHED;
            return;
        }

        BufferInfo *inInfo = *inQueue.begin();
        OMX_BUFFERHEADERTYPE *inHeader = inInfo->mHeader;

        if (inHeader->nFlags & OMX_BUFFERFLAG_EOS) {
            inQueue.erase(inQueue.begin());
            inInfo->mOwnedByUs = false;
            notifyEmptyBufferDone(inHeader);

            mEOSStatus = INPUT_EOS_SEEN;
            continue;
        }

        mCurrentTimeUs = inHeader->nTimeStamp;

        const uint8_t *fragPtr;
        size_t fragSize;
        findNALFragment(inHeader, &fragPtr, &fragSize);

        bool releaseFragment;
        OMX_BUFFERHEADERTYPE *outHeader;
        status_t err = decodeFragment(
                fragPtr, fragSize,
                &releaseFragment, &outHeader);

        if (releaseFragment) {
            CHECK_GE(inHeader->nFilledLen, fragSize + 4);

            inHeader->nOffset += fragSize + 4;
            inHeader->nFilledLen -= fragSize + 4;

            if (inHeader->nFilledLen == 0) {
                inInfo->mOwnedByUs = false;
                inQueue.erase(inQueue.begin());
                inInfo = NULL;
                notifyEmptyBufferDone(inHeader);
                inHeader = NULL;
            }
        }

        if (outHeader != NULL) {
            List<BufferInfo *>::iterator it = outQueue.begin();
            while ((*it)->mHeader != outHeader) {
                ++it;
            }

            BufferInfo *outInfo = *it;
            outInfo->mOwnedByUs = false;
            outQueue.erase(it);
            outInfo = NULL;

            notifyFillBufferDone(outHeader);
            outHeader = NULL;
            return;
        }

        if (err == INFO_FORMAT_CHANGED) {
            return;
        }

        if (err != OK) {
            notify(OMX_EventError, OMX_ErrorUndefined, err, NULL);
            return;
        }
    }
}

status_t SoftAVC::decodeFragment(
        const uint8_t *fragPtr, size_t fragSize,
        bool *releaseFragment,
        OMX_BUFFERHEADERTYPE **outHeader) {
    *releaseFragment = true;
    *outHeader = NULL;

    int nalType;
    int nalRefIdc;
    AVCDec_Status res =
        PVAVCDecGetNALType(
                const_cast<uint8_t *>(fragPtr), fragSize,
                &nalType, &nalRefIdc);

    if (res != AVCDEC_SUCCESS) {
        LOGV("cannot determine nal type");
        return ERROR_MALFORMED;
    }

    if (nalType != AVC_NALTYPE_SPS && nalType != AVC_NALTYPE_PPS
            && (!mSPSSeen || !mPPSSeen)) {
        // We haven't seen SPS or PPS yet.
        return OK;
    }

    switch (nalType) {
        case AVC_NALTYPE_SPS:
        {
            mSPSSeen = true;

            res = PVAVCDecSeqParamSet(
                    mHandle, const_cast<uint8_t *>(fragPtr),
                    fragSize);

            if (res != AVCDEC_SUCCESS) {
                return ERROR_MALFORMED;
            }

            AVCDecObject *pDecVid = (AVCDecObject *)mHandle->AVCObject;

            int32_t width =
                (pDecVid->seqParams[0]->pic_width_in_mbs_minus1 + 1) * 16;

            int32_t height =
                (pDecVid->seqParams[0]->pic_height_in_map_units_minus1 + 1) * 16;

            int32_t crop_left, crop_right, crop_top, crop_bottom;
            if (pDecVid->seqParams[0]->frame_cropping_flag)
            {
                crop_left = 2 * pDecVid->seqParams[0]->frame_crop_left_offset;
                crop_right =
                    width - (2 * pDecVid->seqParams[0]->frame_crop_right_offset + 1);

                if (pDecVid->seqParams[0]->frame_mbs_only_flag)
                {
                    crop_top = 2 * pDecVid->seqParams[0]->frame_crop_top_offset;
                    crop_bottom =
                        height -
                        (2 * pDecVid->seqParams[0]->frame_crop_bottom_offset + 1);
                }
                else
                {
                    crop_top = 4 * pDecVid->seqParams[0]->frame_crop_top_offset;
                    crop_bottom =
                        height -
                        (4 * pDecVid->seqParams[0]->frame_crop_bottom_offset + 1);
                }
            } else {
                crop_bottom = height - 1;
                crop_right = width - 1;
                crop_top = crop_left = 0;
            }

            status_t err = OK;

            if (mWidth != width || mHeight != height) {
                mWidth = width;
                mHeight = height;

                updatePortDefinitions();

                notify(OMX_EventPortSettingsChanged, 1, 0, NULL);
                mOutputPortSettingsChange = AWAITING_DISABLED;

                err = INFO_FORMAT_CHANGED;
            }

            if (mCropLeft != crop_left
                    || mCropTop != crop_top
                    || mCropRight != crop_right
                    || mCropBottom != crop_bottom) {
                mCropLeft = crop_left;
                mCropTop = crop_top;
                mCropRight = crop_right;
                mCropBottom = crop_bottom;

                notify(OMX_EventPortSettingsChanged,
                       1,
                       OMX_IndexConfigCommonOutputCrop,
                       NULL);
            }

            return err;
        }

        case AVC_NALTYPE_PPS:
        {
            mPPSSeen = true;

            res = PVAVCDecPicParamSet(
                    mHandle, const_cast<uint8_t *>(fragPtr),
                    fragSize);

            if (res != AVCDEC_SUCCESS) {
                LOGV("PVAVCDecPicParamSet returned error %d", res);
                return ERROR_MALFORMED;
            }

            return OK;
        }

        case AVC_NALTYPE_SLICE:
        case AVC_NALTYPE_IDR:
        {
            res = PVAVCDecodeSlice(
                    mHandle, const_cast<uint8_t *>(fragPtr),
                    fragSize);

            if (res == AVCDEC_PICTURE_OUTPUT_READY) {
                *releaseFragment = false;

                if (!drainOutputBuffer(outHeader)) {
                    return UNKNOWN_ERROR;
                }

                return OK;
            }

            if (res == AVCDEC_PICTURE_READY || res == AVCDEC_SUCCESS) {
                return OK;
            } else {
                LOGV("PVAVCDecodeSlice returned error %d", res);
                return ERROR_MALFORMED;
            }
        }

        case AVC_NALTYPE_SEI:
        {
            res = PVAVCDecSEI(
                    mHandle, const_cast<uint8_t *>(fragPtr),
                    fragSize);

            if (res != AVCDEC_SUCCESS) {
                return ERROR_MALFORMED;
            }

            return OK;
        }

        case AVC_NALTYPE_AUD:
        case AVC_NALTYPE_FILL:
        case AVC_NALTYPE_EOSEQ:
        {
            return OK;
        }

        default:
        {
            LOGE("Should not be here, unknown nalType %d", nalType);

            return ERROR_MALFORMED;
        }
    }

    return OK;
}

bool SoftAVC::drainOutputBuffer(OMX_BUFFERHEADERTYPE **outHeader) {
    int32_t index;
    int32_t Release;
    AVCFrameIO Output;
    Output.YCbCr[0] = Output.YCbCr[1] = Output.YCbCr[2] = NULL;
    AVCDec_Status status =
        PVAVCDecGetOutput(mHandle, &index, &Release, &Output);

    if (status != AVCDEC_SUCCESS) {
        return false;
    }

    PortInfo *port = editPortInfo(1);
    CHECK_GE(index, 0);
    CHECK_LT((size_t)index, port->mBuffers.size());
    CHECK(port->mBuffers.editItemAt(index).mOwnedByUs);

    *outHeader = port->mBuffers.editItemAt(index).mHeader;
    (*outHeader)->nOffset = 0;
    (*outHeader)->nFilledLen = port->mDef.nBufferSize;
    (*outHeader)->nFlags = 0;

    return true;
}

void SoftAVC::onPortFlushCompleted(OMX_U32 portIndex) {
    if (portIndex == 0) {
        PVAVCDecReset(mHandle);

        mEOSStatus = INPUT_DATA_AVAILABLE;
    }
}

void SoftAVC::onPortEnableCompleted(OMX_U32 portIndex, bool enabled) {
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

void SoftAVC::updatePortDefinitions() {
    OMX_PARAM_PORTDEFINITIONTYPE *def = &editPortInfo(0)->mDef;
    def->format.video.nFrameWidth = mWidth;
    def->format.video.nFrameHeight = mHeight;
    def->format.video.nStride = def->format.video.nFrameWidth;
    def->format.video.nSliceHeight = def->format.video.nFrameHeight;

    def = &editPortInfo(1)->mDef;
    def->format.video.nFrameWidth = mWidth;
    def->format.video.nFrameHeight = mHeight;
    def->format.video.nStride = def->format.video.nFrameWidth;
    def->format.video.nSliceHeight = def->format.video.nFrameHeight;

    def->nBufferSize =
        (def->format.video.nFrameWidth
            * def->format.video.nFrameHeight * 3) / 2;
}

// static
int32_t SoftAVC::ActivateSPSWrapper(
        void *userData, unsigned int sizeInMbs, unsigned int numBuffers) {
    return static_cast<SoftAVC *>(userData)->activateSPS(sizeInMbs, numBuffers);
}

// static
int32_t SoftAVC::BindFrameWrapper(
        void *userData, int32_t index, uint8_t **yuv) {
    return static_cast<SoftAVC *>(userData)->bindFrame(index, yuv);
}

// static
void SoftAVC::UnbindFrame(void *userData, int32_t index) {
}

int32_t SoftAVC::activateSPS(
        unsigned int sizeInMbs, unsigned int numBuffers) {
    PortInfo *port = editPortInfo(1);
    CHECK_GE(port->mBuffers.size(), numBuffers);
    CHECK_GE(port->mDef.nBufferSize, (sizeInMbs << 7) * 3);

    return 1;
}

int32_t SoftAVC::bindFrame(int32_t index, uint8_t **yuv) {
    PortInfo *port = editPortInfo(1);

    CHECK_GE(index, 0);
    CHECK_LT((size_t)index, port->mBuffers.size());

    BufferInfo *outBuffer =
        &port->mBuffers.editItemAt(index);

    CHECK(outBuffer->mOwnedByUs);

    outBuffer->mHeader->nTimeStamp = mCurrentTimeUs;
    *yuv = outBuffer->mHeader->pBuffer;

    return 1;
}

}  // namespace android

android::SoftOMXComponent *createSoftOMXComponent(
        const char *name, const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData, OMX_COMPONENTTYPE **component) {
    return new android::SoftAVC(name, callbacks, appData, component);
}
