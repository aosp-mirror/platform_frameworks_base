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

#include "AMRWBDecoder.h"

#include "pvamrwbdecoder.h"

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>

namespace android {

static const int32_t kNumSamplesPerFrame = 320;
static const int32_t kSampleRate = 16000;

AMRWBDecoder::AMRWBDecoder(const sp<MediaSource> &source)
    : mSource(source),
      mStarted(false),
      mBufferGroup(NULL),
      mState(NULL),
      mDecoderBuf(NULL),
      mDecoderCookie(NULL),
      mAnchorTimeUs(0),
      mNumSamplesOutput(0),
      mInputBuffer(NULL) {
}

AMRWBDecoder::~AMRWBDecoder() {
    if (mStarted) {
        stop();
    }
}

status_t AMRWBDecoder::start(MetaData *params) {
    CHECK(!mStarted);

    mBufferGroup = new MediaBufferGroup;
    mBufferGroup->add_buffer(
            new MediaBuffer(kNumSamplesPerFrame * sizeof(int16_t)));

    int32_t memReq = pvDecoder_AmrWbMemRequirements();
    mDecoderBuf = malloc(memReq);

    pvDecoder_AmrWb_Init(&mState, mDecoderBuf, &mDecoderCookie);

    mSource->start();

    mAnchorTimeUs = 0;
    mNumSamplesOutput = 0;
    mStarted = true;

    return OK;
}

status_t AMRWBDecoder::stop() {
    CHECK(mStarted);

    if (mInputBuffer) {
        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    delete mBufferGroup;
    mBufferGroup = NULL;

    free(mDecoderBuf);
    mDecoderBuf = NULL;

    mSource->stop();

    mStarted = false;

    return OK;
}

sp<MetaData> AMRWBDecoder::getFormat() {
    sp<MetaData> srcFormat = mSource->getFormat();

    int32_t numChannels;
    int32_t sampleRate;

    CHECK(srcFormat->findInt32(kKeyChannelCount, &numChannels));
    CHECK_EQ(numChannels, 1);

    CHECK(srcFormat->findInt32(kKeySampleRate, &sampleRate));
    CHECK_EQ(sampleRate, kSampleRate);

    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_RAW);
    meta->setInt32(kKeyChannelCount, numChannels);
    meta->setInt32(kKeySampleRate, sampleRate);

    int64_t durationUs;
    if (srcFormat->findInt64(kKeyDuration, &durationUs)) {
        meta->setInt64(kKeyDuration, durationUs);
    }

    meta->setCString(kKeyDecoderComponent, "AMRWBDecoder");

    return meta;
}

static size_t getFrameSize(unsigned FT) {
    static const size_t kFrameSizeWB[9] = {
        132, 177, 253, 285, 317, 365, 397, 461, 477
    };

    size_t frameSize = kFrameSizeWB[FT];

    // Round up bits to bytes and add 1 for the header byte.
    frameSize = (frameSize + 7) / 8 + 1;

    return frameSize;
}

status_t AMRWBDecoder::read(
        MediaBuffer **out, const ReadOptions *options) {
    status_t err;

    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode seekMode;
    if (options && options->getSeekTo(&seekTimeUs, &seekMode)) {
        CHECK(seekTimeUs >= 0);

        mNumSamplesOutput = 0;

        if (mInputBuffer) {
            mInputBuffer->release();
            mInputBuffer = NULL;
        }
    } else {
        seekTimeUs = -1;
    }

    if (mInputBuffer == NULL) {
        err = mSource->read(&mInputBuffer, options);

        if (err != OK) {
            return err;
        }

        int64_t timeUs;
        if (mInputBuffer->meta_data()->findInt64(kKeyTime, &timeUs)) {
            mAnchorTimeUs = timeUs;
            mNumSamplesOutput = 0;
        } else {
            // We must have a new timestamp after seeking.
            CHECK(seekTimeUs < 0);
        }
    }

    MediaBuffer *buffer;
    CHECK_EQ(mBufferGroup->acquire_buffer(&buffer), OK);

    const uint8_t *inputPtr =
        (const uint8_t *)mInputBuffer->data() + mInputBuffer->range_offset();

    int16 mode = ((inputPtr[0] >> 3) & 0x0f);
    size_t frameSize = getFrameSize(mode);
    CHECK(mInputBuffer->range_length() >= frameSize);

    int16 frameType;
    RX_State rx_state;
    mime_unsorting(
            const_cast<uint8_t *>(&inputPtr[1]),
            mInputSampleBuffer,
            &frameType, &mode, 1, &rx_state);

    int16_t *outPtr = (int16_t *)buffer->data();

    int16_t numSamplesOutput;
    pvDecoder_AmrWb(
            mode, mInputSampleBuffer,
            outPtr,
            &numSamplesOutput,
            mDecoderBuf, frameType, mDecoderCookie);

    CHECK_EQ(numSamplesOutput, kNumSamplesPerFrame);

    for (int i = 0; i < kNumSamplesPerFrame; ++i) {
        /* Delete the 2 LSBs (14-bit output) */
        outPtr[i] &= 0xfffC;
    }

    buffer->set_range(0, numSamplesOutput * sizeof(int16_t));

    mInputBuffer->set_range(
            mInputBuffer->range_offset() + frameSize,
            mInputBuffer->range_length() - frameSize);

    if (mInputBuffer->range_length() == 0) {
        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    buffer->meta_data()->setInt64(
            kKeyTime,
            mAnchorTimeUs
                + (mNumSamplesOutput * 1000000) / kSampleRate);

    mNumSamplesOutput += kNumSamplesPerFrame;

    *out = buffer;

    return OK;
}

}  // namespace android
