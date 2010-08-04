/*
 * Copyright (C) 2009 The Android Open Source Project
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
#define LOG_TAG "AVCDecoder"
#include <utils/Log.h>

#include "AVCDecoder.h"

#include "avcdec_api.h"
#include "avcdec_int.h"

#include <OMX_Component.h>

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/Utils.h>

namespace android {

static const char kStartCode[4] = { 0x00, 0x00, 0x00, 0x01 };

static int32_t Malloc(void *userData, int32_t size, int32_t attrs) {
    return reinterpret_cast<int32_t>(malloc(size));
}

static void Free(void *userData, int32_t ptr) {
    free(reinterpret_cast<void *>(ptr));
}

AVCDecoder::AVCDecoder(const sp<MediaSource> &source)
    : mSource(source),
      mStarted(false),
      mHandle(new tagAVCHandle),
      mInputBuffer(NULL),
      mAnchorTimeUs(0),
      mNumSamplesOutput(0),
      mPendingSeekTimeUs(-1),
      mPendingSeekMode(MediaSource::ReadOptions::SEEK_CLOSEST_SYNC),
      mTargetTimeUs(-1),
      mSPSSeen(false),
      mPPSSeen(false) {
    memset(mHandle, 0, sizeof(tagAVCHandle));
    mHandle->AVCObject = NULL;
    mHandle->userData = this;
    mHandle->CBAVC_DPBAlloc = ActivateSPSWrapper;
    mHandle->CBAVC_FrameBind = BindFrameWrapper;
    mHandle->CBAVC_FrameUnbind = UnbindFrame;
    mHandle->CBAVC_Malloc = Malloc;
    mHandle->CBAVC_Free = Free;

    mFormat = new MetaData;
    mFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_RAW);
    int32_t width, height;
    CHECK(mSource->getFormat()->findInt32(kKeyWidth, &width));
    CHECK(mSource->getFormat()->findInt32(kKeyHeight, &height));
    mFormat->setInt32(kKeyWidth, width);
    mFormat->setInt32(kKeyHeight, height);
    mFormat->setInt32(kKeyColorFormat, OMX_COLOR_FormatYUV420Planar);
    mFormat->setCString(kKeyDecoderComponent, "AVCDecoder");

    int64_t durationUs;
    if (mSource->getFormat()->findInt64(kKeyDuration, &durationUs)) {
        mFormat->setInt64(kKeyDuration, durationUs);
    }
}

AVCDecoder::~AVCDecoder() {
    if (mStarted) {
        stop();
    }

    PVAVCCleanUpDecoder(mHandle);

    delete mHandle;
    mHandle = NULL;
}

status_t AVCDecoder::start(MetaData *) {
    CHECK(!mStarted);

    uint32_t type;
    const void *data;
    size_t size;
    sp<MetaData> meta = mSource->getFormat();
    if (meta->findData(kKeyAVCC, &type, &data, &size)) {
        // Parse the AVCDecoderConfigurationRecord

        const uint8_t *ptr = (const uint8_t *)data;

        CHECK(size >= 7);
        CHECK_EQ(ptr[0], 1);  // configurationVersion == 1
        uint8_t profile = ptr[1];
        uint8_t level = ptr[3];

        // There is decodable content out there that fails the following
        // assertion, let's be lenient for now...
        // CHECK((ptr[4] >> 2) == 0x3f);  // reserved

        size_t lengthSize = 1 + (ptr[4] & 3);

        // commented out check below as H264_QVGA_500_NO_AUDIO.3gp
        // violates it...
        // CHECK((ptr[5] >> 5) == 7);  // reserved

        size_t numSeqParameterSets = ptr[5] & 31;

        ptr += 6;
        size -= 6;

        for (size_t i = 0; i < numSeqParameterSets; ++i) {
            CHECK(size >= 2);
            size_t length = U16_AT(ptr);

            ptr += 2;
            size -= 2;

            CHECK(size >= length);

            addCodecSpecificData(ptr, length);

            ptr += length;
            size -= length;
        }

        CHECK(size >= 1);
        size_t numPictureParameterSets = *ptr;
        ++ptr;
        --size;

        for (size_t i = 0; i < numPictureParameterSets; ++i) {
            CHECK(size >= 2);
            size_t length = U16_AT(ptr);

            ptr += 2;
            size -= 2;

            CHECK(size >= length);

            addCodecSpecificData(ptr, length);

            ptr += length;
            size -= length;
        }
    }

    mSource->start();

    mAnchorTimeUs = 0;
    mNumSamplesOutput = 0;
    mPendingSeekTimeUs = -1;
    mPendingSeekMode = ReadOptions::SEEK_CLOSEST_SYNC;
    mTargetTimeUs = -1;
    mSPSSeen = false;
    mPPSSeen = false;
    mStarted = true;

    return OK;
}

void AVCDecoder::addCodecSpecificData(const uint8_t *data, size_t size) {
    MediaBuffer *buffer = new MediaBuffer(size + 4);
    memcpy(buffer->data(), kStartCode, 4);
    memcpy((uint8_t *)buffer->data() + 4, data, size);
    buffer->set_range(0, size + 4);

    mCodecSpecificData.push(buffer);
}

status_t AVCDecoder::stop() {
    CHECK(mStarted);

    for (size_t i = 0; i < mCodecSpecificData.size(); ++i) {
        (*mCodecSpecificData.editItemAt(i)).release();
    }
    mCodecSpecificData.clear();

    if (mInputBuffer) {
        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    mSource->stop();

    releaseFrames();

    mStarted = false;

    return OK;
}

sp<MetaData> AVCDecoder::getFormat() {
    return mFormat;
}

static void findNALFragment(
        const MediaBuffer *buffer, const uint8_t **fragPtr, size_t *fragSize) {
    const uint8_t *data =
        (const uint8_t *)buffer->data() + buffer->range_offset();

    size_t size = buffer->range_length();

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

status_t AVCDecoder::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        LOGV("seek requested to %lld us (%.2f secs)", seekTimeUs, seekTimeUs / 1E6);

        CHECK(seekTimeUs >= 0);
        mPendingSeekTimeUs = seekTimeUs;
        mPendingSeekMode = mode;

        if (mInputBuffer) {
            mInputBuffer->release();
            mInputBuffer = NULL;
        }

        PVAVCDecReset(mHandle);
    }

    if (mInputBuffer == NULL) {
        LOGV("fetching new input buffer.");

        bool seeking = false;

        if (!mCodecSpecificData.isEmpty()) {
            mInputBuffer = mCodecSpecificData.editItemAt(0);
            mCodecSpecificData.removeAt(0);
        } else {
            for (;;) {
                if (mPendingSeekTimeUs >= 0) {
                    LOGV("reading data from timestamp %lld (%.2f secs)",
                         mPendingSeekTimeUs, mPendingSeekTimeUs / 1E6);
                }

                ReadOptions seekOptions;
                if (mPendingSeekTimeUs >= 0) {
                    seeking = true;

                    seekOptions.setSeekTo(mPendingSeekTimeUs, mPendingSeekMode);
                    mPendingSeekTimeUs = -1;
                }
                status_t err = mSource->read(&mInputBuffer, &seekOptions);
                seekOptions.clearSeekTo();

                if (err != OK) {
                    return err;
                }

                if (mInputBuffer->range_length() > 0) {
                    break;
                }

                mInputBuffer->release();
                mInputBuffer = NULL;
            }
        }

        if (seeking) {
            int64_t targetTimeUs;
            if (mInputBuffer->meta_data()->findInt64(kKeyTargetTime, &targetTimeUs)
                    && targetTimeUs >= 0) {
                mTargetTimeUs = targetTimeUs;
            } else {
                mTargetTimeUs = -1;
            }
        }
    }

    const uint8_t *fragPtr;
    size_t fragSize;
    findNALFragment(mInputBuffer, &fragPtr, &fragSize);

    bool releaseFragment = true;
    status_t err = UNKNOWN_ERROR;

    int nalType;
    int nalRefIdc;
    AVCDec_Status res =
        PVAVCDecGetNALType(
                const_cast<uint8_t *>(fragPtr), fragSize,
                &nalType, &nalRefIdc);

    if (res != AVCDEC_SUCCESS) {
        LOGE("cannot determine nal type");
    } else if (nalType == AVC_NALTYPE_SPS || nalType == AVC_NALTYPE_PPS
                || (mSPSSeen && mPPSSeen)) {
        switch (nalType) {
            case AVC_NALTYPE_SPS:
            {
                mSPSSeen = true;

                res = PVAVCDecSeqParamSet(
                        mHandle, const_cast<uint8_t *>(fragPtr),
                        fragSize);

                if (res != AVCDEC_SUCCESS) {
                    break;
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

                int32_t aligned_width = (crop_right - crop_left + 1 + 15) & ~15;
                int32_t aligned_height = (crop_bottom - crop_top + 1 + 15) & ~15;

                int32_t oldWidth, oldHeight;
                CHECK(mFormat->findInt32(kKeyWidth, &oldWidth));
                CHECK(mFormat->findInt32(kKeyHeight, &oldHeight));

                if (oldWidth != aligned_width || oldHeight != aligned_height) {
                    mFormat->setInt32(kKeyWidth, aligned_width);
                    mFormat->setInt32(kKeyHeight, aligned_height);

                    err = INFO_FORMAT_CHANGED;
                } else {
                    *out = new MediaBuffer(0);
                    err = OK;
                }
                break;
            }

            case AVC_NALTYPE_PPS:
            {
                mPPSSeen = true;

                res = PVAVCDecPicParamSet(
                        mHandle, const_cast<uint8_t *>(fragPtr),
                        fragSize);

                if (res != AVCDEC_SUCCESS) {
                    break;
                }

                *out = new MediaBuffer(0);

                err = OK;
                break;
            }

            case AVC_NALTYPE_SLICE:
            case AVC_NALTYPE_IDR:
            {
                res = PVAVCDecodeSlice(
                        mHandle, const_cast<uint8_t *>(fragPtr),
                        fragSize);

                if (res == AVCDEC_PICTURE_OUTPUT_READY) {
                    int32_t index;
                    int32_t Release;
                    AVCFrameIO Output;
                    Output.YCbCr[0] = Output.YCbCr[1] = Output.YCbCr[2] = NULL;

                    CHECK_EQ(PVAVCDecGetOutput(mHandle, &index, &Release, &Output),
                             AVCDEC_SUCCESS);

                    CHECK(index >= 0);
                    CHECK(index < (int32_t)mFrames.size());

                    MediaBuffer *mbuf = mFrames.editItemAt(index);

                    bool skipFrame = false;

                    if (mTargetTimeUs >= 0) {
                        int64_t timeUs;
                        CHECK(mbuf->meta_data()->findInt64(kKeyTime, &timeUs));
                        CHECK(timeUs <= mTargetTimeUs);

                        if (timeUs < mTargetTimeUs) {
                            // We're still waiting for the frame with the matching
                            // timestamp and we won't return the current one.
                            skipFrame = true;

                            LOGV("skipping frame at %lld us", timeUs);
                        } else {
                            LOGV("found target frame at %lld us", timeUs);

                            mTargetTimeUs = -1;
                        }
                    }

                    if (!skipFrame) {
                        *out = mbuf;
                        (*out)->set_range(0, (*out)->size());
                        (*out)->add_ref();
                    } else {
                        *out = new MediaBuffer(0);
                    }

                    // Do _not_ release input buffer yet.

                    releaseFragment = false;
                    err = OK;
                    break;
                }

                if (res == AVCDEC_PICTURE_READY || res == AVCDEC_SUCCESS) {
                    *out = new MediaBuffer(0);

                    err = OK;
                } else {
                    LOGV("failed to decode frame (res = %d)", res);
                }
                break;
            }

            case AVC_NALTYPE_SEI:
            {
                res = PVAVCDecSEI(
                        mHandle, const_cast<uint8_t *>(fragPtr),
                        fragSize);

                if (res != AVCDEC_SUCCESS) {
                    break;
                }

                *out = new MediaBuffer(0);

                err = OK;
                break;
            }

            case AVC_NALTYPE_AUD:
            case AVC_NALTYPE_FILL:
            {
                *out = new MediaBuffer(0);

                err = OK;
                break;
            }

            default:
            {
                LOGE("Should not be here, unknown nalType %d", nalType);
                CHECK(!"Should not be here");
                break;
            }
        }
    } else {
        // We haven't seen SPS or PPS yet.

        *out = new MediaBuffer(0);
        err = OK;
    }

    if (releaseFragment) {
        size_t offset = mInputBuffer->range_offset();
        if (fragSize + 4 == mInputBuffer->range_length()) {
            mInputBuffer->release();
            mInputBuffer = NULL;
        } else {
            mInputBuffer->set_range(
                    offset + fragSize + 4,
                    mInputBuffer->range_length() - fragSize - 4);
        }
    }

    return err;
}

// static
int32_t AVCDecoder::ActivateSPSWrapper(
        void *userData, unsigned int sizeInMbs, unsigned int numBuffers) {
    return static_cast<AVCDecoder *>(userData)->activateSPS(sizeInMbs, numBuffers);
}

// static
int32_t AVCDecoder::BindFrameWrapper(
        void *userData, int32_t index, uint8_t **yuv) {
    return static_cast<AVCDecoder *>(userData)->bindFrame(index, yuv);
}

// static
void AVCDecoder::UnbindFrame(void *userData, int32_t index) {
}

int32_t AVCDecoder::activateSPS(
        unsigned int sizeInMbs, unsigned int numBuffers) {
    CHECK(mFrames.isEmpty());

    size_t frameSize = (sizeInMbs << 7) * 3;
    for (unsigned int i = 0; i < numBuffers; ++i) {
        MediaBuffer *buffer = new MediaBuffer(frameSize);
        buffer->setObserver(this);

        mFrames.push(buffer);
    }

    return 1;
}

int32_t AVCDecoder::bindFrame(int32_t index, uint8_t **yuv) {
    CHECK(index >= 0);
    CHECK(index < (int32_t)mFrames.size());

    CHECK(mInputBuffer != NULL);
    int64_t timeUs;
    CHECK(mInputBuffer->meta_data()->findInt64(kKeyTime, &timeUs));
    mFrames[index]->meta_data()->setInt64(kKeyTime, timeUs);

    *yuv = (uint8_t *)mFrames[index]->data();

    return 1;
}

void AVCDecoder::releaseFrames() {
    for (size_t i = 0; i < mFrames.size(); ++i) {
        MediaBuffer *buffer = mFrames.editItemAt(i);

        buffer->setObserver(NULL);
        buffer->release();
    }
    mFrames.clear();
}

void AVCDecoder::signalBufferReturned(MediaBuffer *buffer) {
}

}  // namespace android
