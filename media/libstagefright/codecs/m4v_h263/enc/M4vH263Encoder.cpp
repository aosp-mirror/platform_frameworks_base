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

//#define LOG_NDEBUG 0
#define LOG_TAG "M4vH263Encoder"
#include <utils/Log.h>

#include "M4vH263Encoder.h"

#include "mp4enc_api.h"
#include "OMX_Video.h"

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>

namespace android {

static status_t ConvertOmxProfileLevel(
        MP4EncodingMode mode,
        int32_t omxProfile,
        int32_t omxLevel,
        ProfileLevelType* pvProfileLevel) {
    ALOGV("ConvertOmxProfileLevel: %d/%d/%d", mode, omxProfile, omxLevel);
    ProfileLevelType profileLevel;
    if (mode == H263_MODE) {
        switch (omxProfile) {
            case OMX_VIDEO_H263ProfileBaseline:
                if (omxLevel > OMX_VIDEO_H263Level45) {
                    LOGE("Unsupported level (%d) for H263", omxLevel);
                    return BAD_VALUE;
                } else {
                    LOGW("PV does not support level configuration for H263");
                    profileLevel = CORE_PROFILE_LEVEL2;
                    break;
                }
                break;
            default:
                LOGE("Unsupported profile (%d) for H263", omxProfile);
                return BAD_VALUE;
        }
    } else {  // MPEG4
        switch (omxProfile) {
            case OMX_VIDEO_MPEG4ProfileSimple:
                switch (omxLevel) {
                    case OMX_VIDEO_MPEG4Level0b:
                        profileLevel = SIMPLE_PROFILE_LEVEL0;
                        break;
                    case OMX_VIDEO_MPEG4Level1:
                        profileLevel = SIMPLE_PROFILE_LEVEL1;
                        break;
                    case OMX_VIDEO_MPEG4Level2:
                        profileLevel = SIMPLE_PROFILE_LEVEL2;
                        break;
                    case OMX_VIDEO_MPEG4Level3:
                        profileLevel = SIMPLE_PROFILE_LEVEL3;
                        break;
                    default:
                        LOGE("Unsupported level (%d) for MPEG4 simple profile",
                            omxLevel);
                        return BAD_VALUE;
                }
                break;
            case OMX_VIDEO_MPEG4ProfileSimpleScalable:
                switch (omxLevel) {
                    case OMX_VIDEO_MPEG4Level0b:
                        profileLevel = SIMPLE_SCALABLE_PROFILE_LEVEL0;
                        break;
                    case OMX_VIDEO_MPEG4Level1:
                        profileLevel = SIMPLE_SCALABLE_PROFILE_LEVEL1;
                        break;
                    case OMX_VIDEO_MPEG4Level2:
                        profileLevel = SIMPLE_SCALABLE_PROFILE_LEVEL2;
                        break;
                    default:
                        LOGE("Unsupported level (%d) for MPEG4 simple "
                             "scalable profile", omxLevel);
                        return BAD_VALUE;
                }
                break;
            case OMX_VIDEO_MPEG4ProfileCore:
                switch (omxLevel) {
                    case OMX_VIDEO_MPEG4Level1:
                        profileLevel = CORE_PROFILE_LEVEL1;
                        break;
                    case OMX_VIDEO_MPEG4Level2:
                        profileLevel = CORE_PROFILE_LEVEL2;
                        break;
                    default:
                        LOGE("Unsupported level (%d) for MPEG4 core "
                             "profile", omxLevel);
                        return BAD_VALUE;
                }
                break;
            case OMX_VIDEO_MPEG4ProfileCoreScalable:
                switch (omxLevel) {
                    case OMX_VIDEO_MPEG4Level1:
                        profileLevel = CORE_SCALABLE_PROFILE_LEVEL1;
                        break;
                    case OMX_VIDEO_MPEG4Level2:
                        profileLevel = CORE_SCALABLE_PROFILE_LEVEL2;
                        break;
                    case OMX_VIDEO_MPEG4Level3:
                        profileLevel = CORE_SCALABLE_PROFILE_LEVEL3;
                        break;
                    default:
                        LOGE("Unsupported level (%d) for MPEG4 core "
                             "scalable profile", omxLevel);
                        return BAD_VALUE;
                }
                break;
            default:
                LOGE("Unsupported MPEG4 profile (%d)", omxProfile);
                return BAD_VALUE;
        }
    }

    *pvProfileLevel = profileLevel;
    return OK;
}

inline static void ConvertYUV420SemiPlanarToYUV420Planar(
        uint8_t *inyuv, uint8_t* outyuv,
        int32_t width, int32_t height) {

    int32_t outYsize = width * height;
    uint32_t *outy = (uint32_t *)  outyuv;
    uint16_t *outcb = (uint16_t *) (outyuv + outYsize);
    uint16_t *outcr = (uint16_t *) (outyuv + outYsize + (outYsize >> 2));

    /* Y copying */
    memcpy(outy, inyuv, outYsize);

    /* U & V copying */
    uint32_t *inyuv_4 = (uint32_t *) (inyuv + outYsize);
    for (int32_t i = height >> 1; i > 0; --i) {
        for (int32_t j = width >> 2; j > 0; --j) {
            uint32_t temp = *inyuv_4++;
            uint32_t tempU = temp & 0xFF;
            tempU = tempU | ((temp >> 8) & 0xFF00);

            uint32_t tempV = (temp >> 8) & 0xFF;
            tempV = tempV | ((temp >> 16) & 0xFF00);

            // Flip U and V
            *outcb++ = tempV;
            *outcr++ = tempU;
        }
    }
}

M4vH263Encoder::M4vH263Encoder(
        const sp<MediaSource>& source,
        const sp<MetaData>& meta)
    : mSource(source),
      mMeta(meta),
      mNumInputFrames(-1),
      mNextModTimeUs(0),
      mPrevTimestampUs(-1),
      mStarted(false),
      mInputBuffer(NULL),
      mInputFrameData(NULL),
      mGroup(NULL) {

    ALOGI("Construct software M4vH263Encoder");

    mHandle = new tagvideoEncControls;
    memset(mHandle, 0, sizeof(tagvideoEncControls));

    mInitCheck = initCheck(meta);
}

M4vH263Encoder::~M4vH263Encoder() {
    ALOGV("Destruct software M4vH263Encoder");
    if (mStarted) {
        stop();
    }

    delete mEncParams;
    delete mHandle;
}

status_t M4vH263Encoder::initCheck(const sp<MetaData>& meta) {
    ALOGV("initCheck");
    CHECK(meta->findInt32(kKeyWidth, &mVideoWidth));
    CHECK(meta->findInt32(kKeyHeight, &mVideoHeight));
    CHECK(meta->findInt32(kKeyFrameRate, &mVideoFrameRate));
    CHECK(meta->findInt32(kKeyBitRate, &mVideoBitRate));

    // XXX: Add more color format support
    CHECK(meta->findInt32(kKeyColorFormat, &mVideoColorFormat));
    if (mVideoColorFormat != OMX_COLOR_FormatYUV420Planar) {
        if (mVideoColorFormat != OMX_COLOR_FormatYUV420SemiPlanar) {
            LOGE("Color format %d is not supported", mVideoColorFormat);
            return BAD_VALUE;
        }
        // Allocate spare buffer only when color conversion is needed.
        // Assume the color format is OMX_COLOR_FormatYUV420SemiPlanar.
        mInputFrameData =
            (uint8_t *) malloc((mVideoWidth * mVideoHeight * 3 ) >> 1);
        CHECK(mInputFrameData);
    }

    // XXX: Remove this restriction
    if (mVideoWidth % 16 != 0 || mVideoHeight % 16 != 0) {
        LOGE("Video frame size %dx%d must be a multiple of 16",
            mVideoWidth, mVideoHeight);
        return BAD_VALUE;
    }

    mEncParams = new tagvideoEncOptions;
    memset(mEncParams, 0, sizeof(tagvideoEncOptions));
    if (!PVGetDefaultEncOption(mEncParams, 0)) {
        LOGE("Failed to get default encoding parameters");
        return BAD_VALUE;
    }

    // Need to know which role the encoder is in.
    // XXX: Set the mode proper for other types of applications
    //      like streaming or video conference
    const char *mime;
    CHECK(meta->findCString(kKeyMIMEType, &mime));
    CHECK(!strcmp(mime, MEDIA_MIMETYPE_VIDEO_MPEG4) ||
          !strcmp(mime, MEDIA_MIMETYPE_VIDEO_H263));
    if (!strcmp(mime, MEDIA_MIMETYPE_VIDEO_MPEG4)) {
        mEncParams->encMode = COMBINE_MODE_WITH_ERR_RES;
    } else {
        mEncParams->encMode = H263_MODE;
    }
    mEncParams->encWidth[0] = mVideoWidth;
    mEncParams->encHeight[0] = mVideoHeight;
    mEncParams->encFrameRate[0] = mVideoFrameRate;
    mEncParams->rcType = VBR_1;
    mEncParams->vbvDelay = (float)5.0;

    // Set profile and level
    // If profile and level setting is not correct, failure
    // is reported when the encoder is initialized.
    mEncParams->profile_level = CORE_PROFILE_LEVEL2;
    int32_t profile, level;
    if (meta->findInt32(kKeyVideoProfile, &profile) &&
        meta->findInt32(kKeyVideoLevel, &level)) {
        if (OK != ConvertOmxProfileLevel(
                        mEncParams->encMode, profile, level,
                        &mEncParams->profile_level)) {
            return BAD_VALUE;
        }
    }

    mEncParams->packetSize = 32;
    mEncParams->rvlcEnable = PV_OFF;
    mEncParams->numLayers = 1;
    mEncParams->timeIncRes = 1000;
    mEncParams->tickPerSrc = mEncParams->timeIncRes / mVideoFrameRate;

    mEncParams->bitRate[0] = mVideoBitRate;
    mEncParams->iQuant[0] = 15;
    mEncParams->pQuant[0] = 12;
    mEncParams->quantType[0] = 0;
    mEncParams->noFrameSkipped = PV_OFF;

    // Set IDR frame refresh interval
    int32_t iFramesIntervalSec;
    CHECK(meta->findInt32(kKeyIFramesInterval, &iFramesIntervalSec));
    if (iFramesIntervalSec < 0) {
        mEncParams->intraPeriod = -1;
    } else if (iFramesIntervalSec == 0) {
        mEncParams->intraPeriod = 1;  // All I frames
    } else {
        mEncParams->intraPeriod =
            (iFramesIntervalSec * mVideoFrameRate);
    }

    mEncParams->numIntraMB = 0;
    mEncParams->sceneDetect = PV_ON;
    mEncParams->searchRange = 16;
    mEncParams->mv8x8Enable = PV_OFF;
    mEncParams->gobHeaderInterval = 0;
    mEncParams->useACPred = PV_ON;
    mEncParams->intraDCVlcTh = 0;

    mFormat = new MetaData;
    mFormat->setInt32(kKeyWidth, mVideoWidth);
    mFormat->setInt32(kKeyHeight, mVideoHeight);
    mFormat->setInt32(kKeyBitRate, mVideoBitRate);
    mFormat->setInt32(kKeyFrameRate, mVideoFrameRate);
    mFormat->setInt32(kKeyColorFormat, mVideoColorFormat);

    mFormat->setCString(kKeyMIMEType, mime);
    mFormat->setCString(kKeyDecoderComponent, "M4vH263Encoder");
    return OK;
}

status_t M4vH263Encoder::start(MetaData *params) {
    ALOGV("start");
    if (mInitCheck != OK) {
        return mInitCheck;
    }

    if (mStarted) {
        LOGW("Call start() when encoder already started");
        return OK;
    }

    if (!PVInitVideoEncoder(mHandle, mEncParams)) {
        LOGE("Failed to initialize the encoder");
        return UNKNOWN_ERROR;
    }

    mGroup = new MediaBufferGroup();
    int32_t maxSize;
    if (!PVGetMaxVideoFrameSize(mHandle, &maxSize)) {
        maxSize = 256 * 1024;  // Magic #
    }
    ALOGV("Max output buffer size: %d", maxSize);
    mGroup->add_buffer(new MediaBuffer(maxSize));

    mSource->start(params);
    mNumInputFrames = -1;  // 1st frame contains codec specific data
    mStarted = true;

    return OK;
}

status_t M4vH263Encoder::stop() {
    ALOGV("stop");
    if (!mStarted) {
        LOGW("Call stop() when encoder has not started");
        return OK;
    }

    if (mInputBuffer) {
        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    if (mGroup) {
        delete mGroup;
        mGroup = NULL;
    }

    if (mInputFrameData) {
        delete mInputFrameData;
        mInputFrameData = NULL;
    }

    CHECK(PVCleanUpVideoEncoder(mHandle));

    mSource->stop();
    mStarted = false;

    return OK;
}

sp<MetaData> M4vH263Encoder::getFormat() {
    ALOGV("getFormat");
    return mFormat;
}

status_t M4vH263Encoder::read(
        MediaBuffer **out, const ReadOptions *options) {

    *out = NULL;

    MediaBuffer *outputBuffer;
    CHECK_EQ(OK, mGroup->acquire_buffer(&outputBuffer));
    uint8_t *outPtr = (uint8_t *) outputBuffer->data();
    int32_t dataLength = outputBuffer->size();

    // Output codec specific data
    if (mNumInputFrames < 0) {
        if (!PVGetVolHeader(mHandle, outPtr, &dataLength, 0)) {
            LOGE("Failed to get VOL header");
            return UNKNOWN_ERROR;
        }
        ALOGV("Output VOL header: %d bytes", dataLength);
        outputBuffer->meta_data()->setInt32(kKeyIsCodecConfig, 1);
        outputBuffer->set_range(0, dataLength);
        *out = outputBuffer;
        ++mNumInputFrames;
        return OK;
    }

    // Ready for accepting an input video frame
    status_t err = mSource->read(&mInputBuffer, options);
    if (OK != err) {
        if (err != ERROR_END_OF_STREAM) {
            LOGE("Failed to read from data source");
        }
        outputBuffer->release();
        return err;
    }

    if (mInputBuffer->size() - ((mVideoWidth * mVideoHeight * 3) >> 1) != 0) {
        outputBuffer->release();
        mInputBuffer->release();
        mInputBuffer = NULL;
        return UNKNOWN_ERROR;
    }

    int64_t timeUs;
    CHECK(mInputBuffer->meta_data()->findInt64(kKeyTime, &timeUs));

    // When the timestamp of the current sample is the same as that
    // of the previous sample, encoding of the current sample is
    // bypassed, and the output length of the sample is set to 0
    if (mNumInputFrames >= 1 &&
        (mNextModTimeUs > timeUs || mPrevTimestampUs == timeUs)) {
        // Frame arrives too late
        outputBuffer->set_range(0, 0);
        *out = outputBuffer;
        mInputBuffer->release();
        mInputBuffer = NULL;
        return OK;
    }

    // Don't accept out-of-order samples
    CHECK(mPrevTimestampUs < timeUs);
    mPrevTimestampUs = timeUs;

    // Color convert to OMX_COLOR_FormatYUV420Planar if necessary
    outputBuffer->meta_data()->setInt64(kKeyTime, timeUs);
    uint8_t *inPtr = (uint8_t *) mInputBuffer->data();
    if (mVideoColorFormat != OMX_COLOR_FormatYUV420Planar) {
        CHECK(mInputFrameData);
        CHECK(mVideoColorFormat == OMX_COLOR_FormatYUV420SemiPlanar);
        ConvertYUV420SemiPlanarToYUV420Planar(
            inPtr, mInputFrameData, mVideoWidth, mVideoHeight);
        inPtr = mInputFrameData;
    }
    CHECK(inPtr != NULL);

    // Ready for encoding a video frame
    VideoEncFrameIO vin, vout;
    vin.height = ((mVideoHeight + 15) >> 4) << 4;
    vin.pitch  = ((mVideoWidth  + 15) >> 4) << 4;
    vin.timestamp = (timeUs + 500) / 1000; // in ms
    vin.yChan = inPtr;
    vin.uChan = vin.yChan + vin.height * vin.pitch;
    vin.vChan = vin.uChan + ((vin.height * vin.pitch) >> 2);
    unsigned long modTimeMs = 0;
    int32_t nLayer = 0;
    MP4HintTrack hintTrack;
    if (!PVEncodeVideoFrame(mHandle, &vin, &vout,
            &modTimeMs, outPtr, &dataLength, &nLayer) ||
        !PVGetHintTrack(mHandle, &hintTrack)) {
        LOGE("Failed to encode frame or get hink track at frame %lld",
            mNumInputFrames);
        outputBuffer->release();
        mInputBuffer->release();
        mInputBuffer = NULL;
        return UNKNOWN_ERROR;
    }
    CHECK_EQ(NULL, PVGetOverrunBuffer(mHandle));
    if (hintTrack.CodeType == 0) {  // I-frame serves as sync frame
        outputBuffer->meta_data()->setInt32(kKeyIsSyncFrame, 1);
    }

    ++mNumInputFrames;
    mNextModTimeUs = modTimeMs * 1000LL;
    outputBuffer->set_range(0, dataLength);
    *out = outputBuffer;
    mInputBuffer->release();
    mInputBuffer = NULL;
    return OK;
}

void M4vH263Encoder::signalBufferReturned(MediaBuffer *buffer) {
}

}  // namespace android
