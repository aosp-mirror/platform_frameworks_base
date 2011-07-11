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


namespace android {

static const CodecProfileLevel kProfileLevels[] = {
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel1  },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel1b },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel11 },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel12 },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel13 },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel2  },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel21 },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel22 },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel3  },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel31 },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel32 },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel4  },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel41 },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel42 },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel5  },
    { OMX_VIDEO_AVCProfileBaseline, OMX_VIDEO_AVCLevel51 },
};

template<class T>
static void InitOMXParams(T *params) {
    params->nSize = sizeof(T);
    params->nVersion.s.nVersionMajor = 1;
    params->nVersion.s.nVersionMinor = 0;
    params->nVersion.s.nRevision = 0;
    params->nVersion.s.nStep = 0;
}

SoftAVC::SoftAVC(
        const char *name,
        const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData,
        OMX_COMPONENTTYPE **component)
    : SimpleSoftOMXComponent(name, callbacks, appData, component),
      mHandle(NULL),
      mInputBufferCount(0),
      mWidth(320),
      mHeight(240),
      mPictureSize(mWidth * mHeight * 3 / 2),
      mCropLeft(0),
      mCropTop(0),
      mCropWidth(mWidth),
      mCropHeight(mHeight),
      mFirstPicture(NULL),
      mFirstPictureId(-1),
      mPicId(0),
      mHeadersDecoded(false),
      mEOSStatus(INPUT_DATA_AVAILABLE),
      mOutputPortSettingsChange(NONE) {
    initPorts();
    CHECK_EQ(initDecoder(), (status_t)OK);
}

SoftAVC::~SoftAVC() {
    H264SwDecRelease(mHandle);
    mHandle = NULL;

    while (mPicToHeaderMap.size() != 0) {
        OMX_BUFFERHEADERTYPE *header = mPicToHeaderMap.editValueAt(0);
        mPicToHeaderMap.removeItemsAt(0);
        delete header;
        header = NULL;
    }
    List<BufferInfo *> &outQueue = getPortQueue(kOutputPortIndex);
    List<BufferInfo *> &inQueue = getPortQueue(kInputPortIndex);
    CHECK(outQueue.empty());
    CHECK(inQueue.empty());

    delete[] mFirstPicture;
}

void SoftAVC::initPorts() {
    OMX_PARAM_PORTDEFINITIONTYPE def;
    InitOMXParams(&def);

    def.nPortIndex = kInputPortIndex;
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

    def.nPortIndex = kOutputPortIndex;
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
    // Force decoder to output buffers in display order.
    if (H264SwDecInit(&mHandle, 0) == H264SWDEC_OK) {
        return OK;
    }
    return UNKNOWN_ERROR;
}

OMX_ERRORTYPE SoftAVC::internalGetParameter(
        OMX_INDEXTYPE index, OMX_PTR params) {
    switch (index) {
        case OMX_IndexParamVideoPortFormat:
        {
            OMX_VIDEO_PARAM_PORTFORMATTYPE *formatParams =
                (OMX_VIDEO_PARAM_PORTFORMATTYPE *)params;

            if (formatParams->nPortIndex > kOutputPortIndex) {
                return OMX_ErrorUndefined;
            }

            if (formatParams->nIndex != 0) {
                return OMX_ErrorNoMore;
            }

            if (formatParams->nPortIndex == kInputPortIndex) {
                formatParams->eCompressionFormat = OMX_VIDEO_CodingAVC;
                formatParams->eColorFormat = OMX_COLOR_FormatUnused;
                formatParams->xFramerate = 0;
            } else {
                CHECK(formatParams->nPortIndex == kOutputPortIndex);

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

            if (profileLevel->nPortIndex != kInputPortIndex) {
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

            if (formatParams->nPortIndex > kOutputPortIndex) {
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
            rectParams->nWidth = mCropWidth;
            rectParams->nHeight = mCropHeight;

            return OMX_ErrorNone;
        }

        default:
            return OMX_ErrorUnsupportedIndex;
    }
}

void SoftAVC::onQueueFilled(OMX_U32 portIndex) {
    if (mOutputPortSettingsChange != NONE) {
        return;
    }

    if (mEOSStatus == OUTPUT_FRAMES_FLUSHED) {
        return;
    }

    List<BufferInfo *> &inQueue = getPortQueue(kInputPortIndex);
    List<BufferInfo *> &outQueue = getPortQueue(kOutputPortIndex);
    H264SwDecRet ret = H264SWDEC_PIC_RDY;
    status_t err = OK;
    bool portSettingsChanged = false;
    while ((mEOSStatus != INPUT_DATA_AVAILABLE || !inQueue.empty())
            && outQueue.size() == kNumOutputBuffers) {

        if (mEOSStatus == INPUT_EOS_SEEN) {
            drainAllOutputBuffers();
            return;
        }

        BufferInfo *inInfo = *inQueue.begin();
        OMX_BUFFERHEADERTYPE *inHeader = inInfo->mHeader;
        ++mPicId;
        if (inHeader->nFlags & OMX_BUFFERFLAG_EOS) {
            inQueue.erase(inQueue.begin());
            inInfo->mOwnedByUs = false;
            notifyEmptyBufferDone(inHeader);
            mEOSStatus = INPUT_EOS_SEEN;
            continue;
        }

        OMX_BUFFERHEADERTYPE *header = new OMX_BUFFERHEADERTYPE;
        memset(header, 0, sizeof(OMX_BUFFERHEADERTYPE));
        header->nTimeStamp = inHeader->nTimeStamp;
        header->nFlags = inHeader->nFlags;
        mPicToHeaderMap.add(mPicId, header);
        inQueue.erase(inQueue.begin());

        H264SwDecInput inPicture;
        H264SwDecOutput outPicture;
        memset(&inPicture, 0, sizeof(inPicture));
        inPicture.dataLen = inHeader->nFilledLen;
        inPicture.pStream = inHeader->pBuffer + inHeader->nOffset;
        inPicture.picId = mPicId;
        inPicture.intraConcealmentMethod = 1;
        H264SwDecPicture decodedPicture;

        while (inPicture.dataLen > 0) {
            ret = H264SwDecDecode(mHandle, &inPicture, &outPicture);
            if (ret == H264SWDEC_HDRS_RDY_BUFF_NOT_EMPTY ||
                ret == H264SWDEC_PIC_RDY_BUFF_NOT_EMPTY) {
                inPicture.dataLen -= (u32)(outPicture.pStrmCurrPos - inPicture.pStream);
                inPicture.pStream = outPicture.pStrmCurrPos;
                if (ret == H264SWDEC_HDRS_RDY_BUFF_NOT_EMPTY) {
                    mHeadersDecoded = true;
                    H264SwDecInfo decoderInfo;
                    CHECK(H264SwDecGetInfo(mHandle, &decoderInfo) == H264SWDEC_OK);

                    if (handlePortSettingChangeEvent(&decoderInfo)) {
                        portSettingsChanged = true;
                    }

                    if (decoderInfo.croppingFlag &&
                        handleCropRectEvent(&decoderInfo.cropParams)) {
                        portSettingsChanged = true;
                    }
                }
            } else {
                if (portSettingsChanged) {
                    if (H264SwDecNextPicture(mHandle, &decodedPicture, 0)
                        == H264SWDEC_PIC_RDY) {

                        // Save this output buffer; otherwise, it will be
                        // lost during dynamic port reconfiguration because
                        // OpenMAX client will delete _all_ output buffers
                        // in the process.
                        saveFirstOutputBuffer(
                            decodedPicture.picId,
                            (uint8_t *)decodedPicture.pOutputPicture);
                    }
                }
                inPicture.dataLen = 0;
                if (ret < 0) {
                    LOGE("Decoder failed: %d", ret);
                    err = ERROR_MALFORMED;
                }
            }
        }
        inInfo->mOwnedByUs = false;
        notifyEmptyBufferDone(inHeader);

        if (portSettingsChanged) {
            portSettingsChanged = false;
            return;
        }

        if (mFirstPicture && !outQueue.empty()) {
            drainOneOutputBuffer(mFirstPictureId, mFirstPicture);
            delete[] mFirstPicture;
            mFirstPicture = NULL;
            mFirstPictureId = -1;
        }

        while (!outQueue.empty() &&
                mHeadersDecoded &&
                H264SwDecNextPicture(mHandle, &decodedPicture, 0)
                    == H264SWDEC_PIC_RDY) {

            int32_t picId = decodedPicture.picId;
            uint8_t *data = (uint8_t *) decodedPicture.pOutputPicture;
            drainOneOutputBuffer(picId, data);
        }

        if (err != OK) {
            notify(OMX_EventError, OMX_ErrorUndefined, err, NULL);
        }
    }
}

bool SoftAVC::handlePortSettingChangeEvent(const H264SwDecInfo *info) {
    if (mWidth != info->picWidth || mHeight != info->picHeight) {
        mWidth  = info->picWidth;
        mHeight = info->picHeight;
        mPictureSize = mWidth * mHeight * 3 / 2;
        mCropWidth = mWidth;
        mCropHeight = mHeight;
        updatePortDefinitions();
        notify(OMX_EventPortSettingsChanged, 1, 0, NULL);
        mOutputPortSettingsChange = AWAITING_DISABLED;
        return true;
    }

    return false;
}

bool SoftAVC::handleCropRectEvent(const CropParams *crop) {
    if (mCropLeft != crop->cropLeftOffset ||
        mCropTop != crop->cropTopOffset ||
        mCropWidth != crop->cropOutWidth ||
        mCropHeight != crop->cropOutHeight) {
        mCropLeft = crop->cropLeftOffset;
        mCropTop = crop->cropTopOffset;
        mCropWidth = crop->cropOutWidth;
        mCropHeight = crop->cropOutHeight;

        notify(OMX_EventPortSettingsChanged, 1,
                OMX_IndexConfigCommonOutputCrop, NULL);

        return true;
    }
    return false;
}

void SoftAVC::saveFirstOutputBuffer(int32_t picId, uint8_t *data) {
    CHECK(mFirstPicture == NULL);
    mFirstPictureId = picId;

    mFirstPicture = new uint8_t[mPictureSize];
    memcpy(mFirstPicture, data, mPictureSize);
}

void SoftAVC::drainOneOutputBuffer(int32_t picId, uint8_t* data) {
    List<BufferInfo *> &outQueue = getPortQueue(kOutputPortIndex);
    BufferInfo *outInfo = *outQueue.begin();
    outQueue.erase(outQueue.begin());
    OMX_BUFFERHEADERTYPE *outHeader = outInfo->mHeader;
    OMX_BUFFERHEADERTYPE *header = mPicToHeaderMap.valueFor(picId);
    outHeader->nTimeStamp = header->nTimeStamp;
    outHeader->nFlags = header->nFlags;
    outHeader->nFilledLen = mPictureSize;
    memcpy(outHeader->pBuffer + outHeader->nOffset,
            data, mPictureSize);
    mPicToHeaderMap.removeItem(picId);
    delete header;
    outInfo->mOwnedByUs = false;
    notifyFillBufferDone(outHeader);
}

bool SoftAVC::drainAllOutputBuffers() {
    List<BufferInfo *> &outQueue = getPortQueue(kOutputPortIndex);
    H264SwDecPicture decodedPicture;

    while (!outQueue.empty()) {
        BufferInfo *outInfo = *outQueue.begin();
        outQueue.erase(outQueue.begin());
        OMX_BUFFERHEADERTYPE *outHeader = outInfo->mHeader;
        if (mHeadersDecoded &&
            H264SWDEC_PIC_RDY ==
                H264SwDecNextPicture(mHandle, &decodedPicture, 1 /* flush */)) {

            int32_t picId = decodedPicture.picId;
            CHECK(mPicToHeaderMap.indexOfKey(picId) >= 0);

            memcpy(outHeader->pBuffer + outHeader->nOffset,
                decodedPicture.pOutputPicture,
                mPictureSize);

            OMX_BUFFERHEADERTYPE *header = mPicToHeaderMap.valueFor(picId);
            outHeader->nTimeStamp = header->nTimeStamp;
            outHeader->nFlags = header->nFlags;
            outHeader->nFilledLen = mPictureSize;
            mPicToHeaderMap.removeItem(picId);
            delete header;
        } else {
            outHeader->nTimeStamp = 0;
            outHeader->nFilledLen = 0;
            outHeader->nFlags = OMX_BUFFERFLAG_EOS;
            mEOSStatus = OUTPUT_FRAMES_FLUSHED;
        }

        outInfo->mOwnedByUs = false;
        notifyFillBufferDone(outHeader);
    }

    return true;
}

void SoftAVC::onPortFlushCompleted(OMX_U32 portIndex) {
    if (portIndex == kInputPortIndex) {
        mEOSStatus = INPUT_DATA_AVAILABLE;
    }
}

void SoftAVC::onPortEnableCompleted(OMX_U32 portIndex, bool enabled) {
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

}  // namespace android

android::SoftOMXComponent *createSoftOMXComponent(
        const char *name, const OMX_CALLBACKTYPE *callbacks,
        OMX_PTR appData, OMX_COMPONENTTYPE **component) {
    return new android::SoftAVC(name, callbacks, appData, component);
}
