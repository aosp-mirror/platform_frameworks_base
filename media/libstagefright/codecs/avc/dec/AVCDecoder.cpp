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
      mNumSamplesOutput(0) {
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
}

AVCDecoder::~AVCDecoder() {
    if (mStarted) {
        stop();
    }

    delete mHandle;
    mHandle = NULL;
}

status_t AVCDecoder::start(MetaData *) {
    CHECK(!mStarted);

    uint32_t type;
    const void *data;
    size_t size;
    if (mSource->getFormat()->findData(kKeyAVCC, &type, &data, &size)) {
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

    sp<MetaData> params = new MetaData;
    params->setInt32(kKeyWantsNALFragments, true);
    mSource->start(params.get());

    mAnchorTimeUs = 0;
    mNumSamplesOutput = 0;
    mStarted = true;

    return OK;
}

void AVCDecoder::addCodecSpecificData(const uint8_t *data, size_t size) {
    MediaBuffer *buffer = new MediaBuffer(size);
    memcpy(buffer->data(), data, size);
    buffer->set_range(0, size);

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

status_t AVCDecoder::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;

    if (mInputBuffer == NULL) {
        LOGV("fetching new input buffer.");

        if (!mCodecSpecificData.isEmpty()) {
            mInputBuffer = mCodecSpecificData.editItemAt(0);
            mCodecSpecificData.removeAt(0);
        } else {
            for (;;) {
                status_t err = mSource->read(&mInputBuffer);
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
    }

    const uint8_t *inPtr =
        (const uint8_t *)mInputBuffer->data() + mInputBuffer->range_offset();

    int nalType;
    int nalRefIdc;
    AVCDec_Status res =
        PVAVCDecGetNALType(
                const_cast<uint8_t *>(inPtr), mInputBuffer->range_length(),
                &nalType, &nalRefIdc);

    if (res != AVCDEC_SUCCESS) {
        mInputBuffer->release();
        mInputBuffer = NULL;

        return UNKNOWN_ERROR;
    }

    switch (nalType) {
        case AVC_NALTYPE_SPS:
        {
            res = PVAVCDecSeqParamSet(
                    mHandle, const_cast<uint8_t *>(inPtr),
                    mInputBuffer->range_length());

            if (res != AVCDEC_SUCCESS) {
                mInputBuffer->release();
                mInputBuffer = NULL;

                return UNKNOWN_ERROR;
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

            mFormat->setInt32(kKeyWidth, crop_right - crop_left + 1);
            mFormat->setInt32(kKeyHeight, crop_bottom - crop_top + 1);

            mInputBuffer->release();
            mInputBuffer = NULL;

            return INFO_FORMAT_CHANGED;
        }

        case AVC_NALTYPE_PPS:
        {
            res = PVAVCDecPicParamSet(
                    mHandle, const_cast<uint8_t *>(inPtr),
                    mInputBuffer->range_length());

            mInputBuffer->release();
            mInputBuffer = NULL;

            if (res != AVCDEC_SUCCESS) {
                return UNKNOWN_ERROR;
            }

            *out = new MediaBuffer(0);

            return OK;
        }

        case AVC_NALTYPE_SLICE:
        case AVC_NALTYPE_IDR:
        {
            res = PVAVCDecodeSlice(
                    mHandle, const_cast<uint8_t *>(inPtr),
                    mInputBuffer->range_length());

            if (res == AVCDEC_PICTURE_OUTPUT_READY) {
                int32_t index;
                int32_t Release;
                AVCFrameIO Output;
                Output.YCbCr[0] = Output.YCbCr[1] = Output.YCbCr[2] = NULL;
                CHECK_EQ(PVAVCDecGetOutput(
                            mHandle, &index, &Release, &Output),
                         AVCDEC_SUCCESS);

                CHECK(index >= 0);
                CHECK(index < (int32_t)mFrames.size());

                *out = mFrames.editItemAt(index);
                (*out)->set_range(0, (*out)->size());
                (*out)->add_ref();

                // Do _not_ release input buffer yet.

                return OK;
            }

            mInputBuffer->release();
            mInputBuffer = NULL;

            if (res == AVCDEC_PICTURE_READY) {
                *out = new MediaBuffer(0);

                return OK;
            } else {
                return UNKNOWN_ERROR;
            }
        }

        case AVC_NALTYPE_SEI:
        {
            res = PVAVCDecodeSlice(
                    mHandle, const_cast<uint8_t *>(inPtr),
                    mInputBuffer->range_length());

            mInputBuffer->release();
            mInputBuffer = NULL;

            if (res != AVCDEC_SUCCESS) {
                return UNKNOWN_ERROR;
            }

            *out = new MediaBuffer(0);

            return OK;
        }

        default:
        {
            LOGE("Should not be here, unknown nalType %d", nalType);
            CHECK(!"Should not be here");
            break;
        }
    }

    mInputBuffer->release();
    mInputBuffer = NULL;

    return UNKNOWN_ERROR;
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
