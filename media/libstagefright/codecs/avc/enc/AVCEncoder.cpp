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
#define LOG_TAG "AVCEncoder"
#include <utils/Log.h>

#include "AVCEncoder.h"

#include "avcenc_api.h"
#include "avcenc_int.h"
#include "OMX_Video.h"

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>

namespace android {

static status_t ConvertOmxAvcProfileToAvcSpecProfile(
        int32_t omxProfile, AVCProfile* pvProfile) {
    ALOGV("ConvertOmxAvcProfileToAvcSpecProfile: %d", omxProfile);
    switch (omxProfile) {
        case OMX_VIDEO_AVCProfileBaseline:
            *pvProfile = AVC_BASELINE;
            return OK;
        default:
            LOGE("Unsupported omx profile: %d", omxProfile);
    }
    return BAD_VALUE;
}

static status_t ConvertOmxAvcLevelToAvcSpecLevel(
        int32_t omxLevel, AVCLevel *pvLevel) {
    ALOGV("ConvertOmxAvcLevelToAvcSpecLevel: %d", omxLevel);
    AVCLevel level = AVC_LEVEL5_1;
    switch (omxLevel) {
        case OMX_VIDEO_AVCLevel1:
            level = AVC_LEVEL1_B;
            break;
        case OMX_VIDEO_AVCLevel1b:
            level = AVC_LEVEL1;
            break;
        case OMX_VIDEO_AVCLevel11:
            level = AVC_LEVEL1_1;
            break;
        case OMX_VIDEO_AVCLevel12:
            level = AVC_LEVEL1_2;
            break;
        case OMX_VIDEO_AVCLevel13:
            level = AVC_LEVEL1_3;
            break;
        case OMX_VIDEO_AVCLevel2:
            level = AVC_LEVEL2;
            break;
        case OMX_VIDEO_AVCLevel21:
            level = AVC_LEVEL2_1;
            break;
        case OMX_VIDEO_AVCLevel22:
            level = AVC_LEVEL2_2;
            break;
        case OMX_VIDEO_AVCLevel3:
            level = AVC_LEVEL3;
            break;
        case OMX_VIDEO_AVCLevel31:
            level = AVC_LEVEL3_1;
            break;
        case OMX_VIDEO_AVCLevel32:
            level = AVC_LEVEL3_2;
            break;
        case OMX_VIDEO_AVCLevel4:
            level = AVC_LEVEL4;
            break;
        case OMX_VIDEO_AVCLevel41:
            level = AVC_LEVEL4_1;
            break;
        case OMX_VIDEO_AVCLevel42:
            level = AVC_LEVEL4_2;
            break;
        case OMX_VIDEO_AVCLevel5:
            level = AVC_LEVEL5;
            break;
        case OMX_VIDEO_AVCLevel51:
            level = AVC_LEVEL5_1;
            break;
        default:
            LOGE("Unknown omx level: %d", omxLevel);
            return BAD_VALUE;
    }
    *pvLevel = level;
    return OK;
}

inline static void ConvertYUV420SemiPlanarToYUV420Planar(
        uint8_t *inyuv, uint8_t* outyuv,
        int32_t width, int32_t height) {

    int32_t outYsize = width * height;
    uint32_t *outy =  (uint32_t *) outyuv;
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

static int32_t MallocWrapper(
        void *userData, int32_t size, int32_t attrs) {
    return reinterpret_cast<int32_t>(malloc(size));
}

static void FreeWrapper(void *userData, int32_t ptr) {
    free(reinterpret_cast<void *>(ptr));
}

static int32_t DpbAllocWrapper(void *userData,
        unsigned int sizeInMbs, unsigned int numBuffers) {
    AVCEncoder *encoder = static_cast<AVCEncoder *>(userData);
    CHECK(encoder != NULL);
    return encoder->allocOutputBuffers(sizeInMbs, numBuffers);
}

static int32_t BindFrameWrapper(
        void *userData, int32_t index, uint8_t **yuv) {
    AVCEncoder *encoder = static_cast<AVCEncoder *>(userData);
    CHECK(encoder != NULL);
    return encoder->bindOutputBuffer(index, yuv);
}

static void UnbindFrameWrapper(void *userData, int32_t index) {
    AVCEncoder *encoder = static_cast<AVCEncoder *>(userData);
    CHECK(encoder != NULL);
    return encoder->unbindOutputBuffer(index);
}

AVCEncoder::AVCEncoder(
        const sp<MediaSource>& source,
        const sp<MetaData>& meta)
    : mSource(source),
      mMeta(meta),
      mNumInputFrames(-1),
      mPrevTimestampUs(-1),
      mStarted(false),
      mInputBuffer(NULL),
      mInputFrameData(NULL),
      mGroup(NULL) {

    LOGI("Construct software AVCEncoder");

    mHandle = new tagAVCHandle;
    memset(mHandle, 0, sizeof(tagAVCHandle));
    mHandle->AVCObject = NULL;
    mHandle->userData = this;
    mHandle->CBAVC_DPBAlloc = DpbAllocWrapper;
    mHandle->CBAVC_FrameBind = BindFrameWrapper;
    mHandle->CBAVC_FrameUnbind = UnbindFrameWrapper;
    mHandle->CBAVC_Malloc = MallocWrapper;
    mHandle->CBAVC_Free = FreeWrapper;

    mInitCheck = initCheck(meta);
}

AVCEncoder::~AVCEncoder() {
    ALOGV("Destruct software AVCEncoder");
    if (mStarted) {
        stop();
    }

    delete mEncParams;
    delete mHandle;
}

status_t AVCEncoder::initCheck(const sp<MetaData>& meta) {
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

    mEncParams = new tagAVCEncParam;
    memset(mEncParams, 0, sizeof(mEncParams));
    mEncParams->width = mVideoWidth;
    mEncParams->height = mVideoHeight;
    mEncParams->frame_rate = 1000 * mVideoFrameRate;  // In frames/ms!
    mEncParams->rate_control = AVC_ON;
    mEncParams->bitrate = mVideoBitRate;
    mEncParams->initQP = 0;
    mEncParams->init_CBP_removal_delay = 1600;
    mEncParams->CPB_size = (uint32_t) (mVideoBitRate >> 1);

    mEncParams->intramb_refresh = 0;
    mEncParams->auto_scd = AVC_ON;
    mEncParams->out_of_band_param_set = AVC_ON;
    mEncParams->poc_type = 2;
    mEncParams->log2_max_poc_lsb_minus_4 = 12;
    mEncParams->delta_poc_zero_flag = 0;
    mEncParams->offset_poc_non_ref = 0;
    mEncParams->offset_top_bottom = 0;
    mEncParams->num_ref_in_cycle = 0;
    mEncParams->offset_poc_ref = NULL;

    mEncParams->num_ref_frame = 1;
    mEncParams->num_slice_group = 1;
    mEncParams->fmo_type = 0;

    mEncParams->db_filter = AVC_ON;
    mEncParams->disable_db_idc = 0;

    mEncParams->alpha_offset = 0;
    mEncParams->beta_offset = 0;
    mEncParams->constrained_intra_pred = AVC_OFF;

    mEncParams->data_par = AVC_OFF;
    mEncParams->fullsearch = AVC_OFF;
    mEncParams->search_range = 16;
    mEncParams->sub_pel = AVC_OFF;
    mEncParams->submb_pred = AVC_OFF;
    mEncParams->rdopt_mode = AVC_OFF;
    mEncParams->bidir_pred = AVC_OFF;
    int32_t nMacroBlocks = ((((mVideoWidth + 15) >> 4) << 4) *
            (((mVideoHeight + 15) >> 4) << 4)) >> 8;
    uint32_t *sliceGroup = (uint32_t *) malloc(sizeof(uint32_t) * nMacroBlocks);
    for (int ii = 0, idx = 0; ii < nMacroBlocks; ++ii) {
        sliceGroup[ii] = idx++;
        if (idx >= mEncParams->num_slice_group) {
            idx = 0;
        }
    }
    mEncParams->slice_group = sliceGroup;

    mEncParams->use_overrun_buffer = AVC_OFF;

    // Set IDR frame refresh interval
    int32_t iFramesIntervalSec;
    CHECK(meta->findInt32(kKeyIFramesInterval, &iFramesIntervalSec));
    if (iFramesIntervalSec < 0) {
        mEncParams->idr_period = -1;
    } else if (iFramesIntervalSec == 0) {
        mEncParams->idr_period = 1;  // All I frames
    } else {
        mEncParams->idr_period =
            (iFramesIntervalSec * mVideoFrameRate);
    }
    ALOGV("idr_period: %d, I-frames interval: %d seconds, and frame rate: %d",
        mEncParams->idr_period, iFramesIntervalSec, mVideoFrameRate);

    // Set profile and level
    // If profile and level setting is not correct, failure
    // is reported when the encoder is initialized.
    mEncParams->profile = AVC_BASELINE;
    mEncParams->level = AVC_LEVEL3_2;
    int32_t profile, level;
    if (meta->findInt32(kKeyVideoProfile, &profile)) {
        if (OK != ConvertOmxAvcProfileToAvcSpecProfile(
                        profile, &mEncParams->profile)) {
            return BAD_VALUE;
        }
    }
    if (meta->findInt32(kKeyVideoLevel, &level)) {
        if (OK != ConvertOmxAvcLevelToAvcSpecLevel(
                        level, &mEncParams->level)) {
            return BAD_VALUE;
        }
    }


    mFormat = new MetaData;
    mFormat->setInt32(kKeyWidth, mVideoWidth);
    mFormat->setInt32(kKeyHeight, mVideoHeight);
    mFormat->setInt32(kKeyBitRate, mVideoBitRate);
    mFormat->setInt32(kKeyFrameRate, mVideoFrameRate);
    mFormat->setInt32(kKeyColorFormat, mVideoColorFormat);
    mFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);
    mFormat->setCString(kKeyDecoderComponent, "AVCEncoder");
    return OK;
}

status_t AVCEncoder::start(MetaData *params) {
    ALOGV("start");
    if (mInitCheck != OK) {
        return mInitCheck;
    }

    if (mStarted) {
        LOGW("Call start() when encoder already started");
        return OK;
    }

    AVCEnc_Status err;
    err = PVAVCEncInitialize(mHandle, mEncParams, NULL, NULL);
    if (err != AVCENC_SUCCESS) {
        LOGE("Failed to initialize the encoder: %d", err);
        return UNKNOWN_ERROR;
    }

    mGroup = new MediaBufferGroup();
    int32_t maxSize;
    if (AVCENC_SUCCESS !=
        PVAVCEncGetMaxOutputBufferSize(mHandle, &maxSize)) {
        maxSize = 31584;  // Magic #
    }
    mGroup->add_buffer(new MediaBuffer(maxSize));

    mSource->start(params);
    mNumInputFrames = -2;  // 1st two buffers contain SPS and PPS
    mStarted = true;
    mSpsPpsHeaderReceived = false;
    mReadyForNextFrame = true;
    mIsIDRFrame = 0;

    return OK;
}

status_t AVCEncoder::stop() {
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

    PVAVCCleanUpEncoder(mHandle);
    mSource->stop();
    releaseOutputBuffers();
    mStarted = false;

    return OK;
}

void AVCEncoder::releaseOutputBuffers() {
    ALOGV("releaseOutputBuffers");
    for (size_t i = 0; i < mOutputBuffers.size(); ++i) {
        MediaBuffer *buffer = mOutputBuffers.editItemAt(i);
        buffer->setObserver(NULL);
        buffer->release();
    }
    mOutputBuffers.clear();
}

sp<MetaData> AVCEncoder::getFormat() {
    ALOGV("getFormat");
    return mFormat;
}

status_t AVCEncoder::read(
        MediaBuffer **out, const ReadOptions *options) {

    CHECK(!options);
    *out = NULL;

    MediaBuffer *outputBuffer;
    CHECK_EQ(OK, mGroup->acquire_buffer(&outputBuffer));
    uint8_t *outPtr = (uint8_t *) outputBuffer->data();
    uint32_t dataLength = outputBuffer->size();

    if (!mSpsPpsHeaderReceived && mNumInputFrames < 0) {
        // 4 bytes are reserved for holding the start code 0x00000001
        // of the sequence parameter set at the beginning.
        outPtr += 4;
        dataLength -= 4;
    }

    int32_t type;
    AVCEnc_Status encoderStatus = AVCENC_SUCCESS;

    // Combine SPS and PPS and place them in the very first output buffer
    // SPS and PPS are separated by start code 0x00000001
    // Assume that we have exactly one SPS and exactly one PPS.
    while (!mSpsPpsHeaderReceived && mNumInputFrames <= 0) {
        encoderStatus = PVAVCEncodeNAL(mHandle, outPtr, &dataLength, &type);
        if (encoderStatus == AVCENC_WRONG_STATE) {
            mSpsPpsHeaderReceived = true;
            CHECK_EQ(0, mNumInputFrames);  // 1st video frame is 0
        } else {
            switch (type) {
                case AVC_NALTYPE_SPS:
                    ++mNumInputFrames;
                    memcpy((uint8_t *)outputBuffer->data(), "\x00\x00\x00\x01", 4);
                    outputBuffer->set_range(0, dataLength + 4);
                    outPtr += (dataLength + 4);  // 4 bytes for next start code
                    dataLength = outputBuffer->size() -
                            (outputBuffer->range_length() + 4);
                    break;
                case AVC_NALTYPE_PPS:
                    ++mNumInputFrames;
                    memcpy(((uint8_t *) outputBuffer->data()) +
                            outputBuffer->range_length(),
                            "\x00\x00\x00\x01", 4);
                    outputBuffer->set_range(0,
                            dataLength + outputBuffer->range_length() + 4);
                    outputBuffer->meta_data()->setInt32(kKeyIsCodecConfig, 1);
                    outputBuffer->meta_data()->setInt64(kKeyTime, 0);
                    *out = outputBuffer;
                    return OK;
                default:
                    LOGE("Nal type (%d) other than SPS/PPS is unexpected", type);
                    return UNKNOWN_ERROR;
            }
        }
    }

    // Get next input video frame
    if (mReadyForNextFrame) {
        if (mInputBuffer) {
            mInputBuffer->release();
            mInputBuffer = NULL;
        }
        status_t err = mSource->read(&mInputBuffer, options);
        if (err != OK) {
            if (err != ERROR_END_OF_STREAM) {
                LOGE("Failed to read input video frame: %d", err);
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
        outputBuffer->meta_data()->setInt64(kKeyTime, timeUs);

        // When the timestamp of the current sample is the same as
        // that of the previous sample, the encoding of the sample
        // is bypassed, and the output length is set to 0.
        if (mNumInputFrames >= 1 && mPrevTimestampUs == timeUs) {
            // Frame arrives too late
            mInputBuffer->release();
            mInputBuffer = NULL;
            outputBuffer->set_range(0, 0);
            *out = outputBuffer;
            return OK;
        }

        // Don't accept out-of-order samples
        CHECK(mPrevTimestampUs < timeUs);
        mPrevTimestampUs = timeUs;

        AVCFrameIO videoInput;
        memset(&videoInput, 0, sizeof(videoInput));
        videoInput.height = ((mVideoHeight  + 15) >> 4) << 4;
        videoInput.pitch = ((mVideoWidth + 15) >> 4) << 4;
        videoInput.coding_timestamp = (timeUs + 500) / 1000;  // in ms
        uint8_t *inputData = (uint8_t *) mInputBuffer->data();

        if (mVideoColorFormat != OMX_COLOR_FormatYUV420Planar) {
            CHECK(mInputFrameData);
            CHECK(mVideoColorFormat == OMX_COLOR_FormatYUV420SemiPlanar);
            ConvertYUV420SemiPlanarToYUV420Planar(
                inputData, mInputFrameData, mVideoWidth, mVideoHeight);
            inputData = mInputFrameData;
        }
        CHECK(inputData != NULL);
        videoInput.YCbCr[0] = inputData;
        videoInput.YCbCr[1] = videoInput.YCbCr[0] + videoInput.height * videoInput.pitch;
        videoInput.YCbCr[2] = videoInput.YCbCr[1] +
            ((videoInput.height * videoInput.pitch) >> 2);
        videoInput.disp_order = mNumInputFrames;

        encoderStatus = PVAVCEncSetInput(mHandle, &videoInput);
        if (encoderStatus == AVCENC_SUCCESS ||
            encoderStatus == AVCENC_NEW_IDR) {
            mReadyForNextFrame = false;
            ++mNumInputFrames;
            if (encoderStatus == AVCENC_NEW_IDR) {
                mIsIDRFrame = 1;
            }
        } else {
            if (encoderStatus < AVCENC_SUCCESS) {
                outputBuffer->release();
                return UNKNOWN_ERROR;
            } else {
                outputBuffer->set_range(0, 0);
                *out = outputBuffer;
                return OK;
            }
        }
    }

    // Encode an input video frame
    CHECK(encoderStatus == AVCENC_SUCCESS ||
          encoderStatus == AVCENC_NEW_IDR);
    dataLength = outputBuffer->size();  // Reset the output buffer length
    encoderStatus = PVAVCEncodeNAL(mHandle, outPtr, &dataLength, &type);
    if (encoderStatus == AVCENC_SUCCESS) {
        outputBuffer->meta_data()->setInt32(kKeyIsSyncFrame, mIsIDRFrame);
        CHECK_EQ(NULL, PVAVCEncGetOverrunBuffer(mHandle));
    } else if (encoderStatus == AVCENC_PICTURE_READY) {
        CHECK_EQ(NULL, PVAVCEncGetOverrunBuffer(mHandle));
        if (mIsIDRFrame) {
            outputBuffer->meta_data()->setInt32(kKeyIsSyncFrame, mIsIDRFrame);
            mIsIDRFrame = 0;
            ALOGV("Output an IDR frame");
        }
        mReadyForNextFrame = true;
        AVCFrameIO recon;
        if (PVAVCEncGetRecon(mHandle, &recon) == AVCENC_SUCCESS) {
            PVAVCEncReleaseRecon(mHandle, &recon);
        }
    } else {
        dataLength = 0;
        mReadyForNextFrame = true;
    }
    if (encoderStatus < AVCENC_SUCCESS) {
        outputBuffer->release();
        return UNKNOWN_ERROR;
    }

    outputBuffer->set_range(0, dataLength);
    *out = outputBuffer;
    return OK;
}

int32_t AVCEncoder::allocOutputBuffers(
        unsigned int sizeInMbs, unsigned int numBuffers) {
    CHECK(mOutputBuffers.isEmpty());
    size_t frameSize = (sizeInMbs << 7) * 3;
    for (unsigned int i = 0; i <  numBuffers; ++i) {
        MediaBuffer *buffer = new MediaBuffer(frameSize);
        buffer->setObserver(this);
        mOutputBuffers.push(buffer);
    }

    return 1;
}

void AVCEncoder::unbindOutputBuffer(int32_t index) {
    CHECK(index >= 0);
}

int32_t AVCEncoder::bindOutputBuffer(int32_t index, uint8_t **yuv) {
    CHECK(index >= 0);
    CHECK(index < (int32_t) mOutputBuffers.size());
    int64_t timeUs;
    CHECK(mInputBuffer->meta_data()->findInt64(kKeyTime, &timeUs));
    mOutputBuffers[index]->meta_data()->setInt64(kKeyTime, timeUs);

    *yuv = (uint8_t *) mOutputBuffers[index]->data();

    return 1;
}

void AVCEncoder::signalBufferReturned(MediaBuffer *buffer) {
}

}  // namespace android
