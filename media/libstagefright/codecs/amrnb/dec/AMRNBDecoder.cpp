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

#include "AMRNBDecoder.h"

#include "gsmamr_dec.h"

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>

namespace android {

static const int32_t kNumSamplesPerFrame = 160;
static const int32_t kSampleRate = 8000;

AMRNBDecoder::AMRNBDecoder(const sp<MediaSource> &source)
    : mSource(source),
      mStarted(false),
      mBufferGroup(NULL),
      mState(NULL),
      mAnchorTimeUs(0),
      mNumSamplesOutput(0),
      mInputBuffer(NULL) {
}

AMRNBDecoder::~AMRNBDecoder() {
    if (mStarted) {
        stop();
    }
}

status_t AMRNBDecoder::start(MetaData *params) {
    CHECK(!mStarted);

    mBufferGroup = new MediaBufferGroup;
    mBufferGroup->add_buffer(
            new MediaBuffer(kNumSamplesPerFrame * sizeof(int16_t)));

    CHECK_EQ(GSMInitDecode(&mState, (Word8 *)"AMRNBDecoder"), 0);

    mSource->start();

    mAnchorTimeUs = 0;
    mNumSamplesOutput = 0;
    mStarted = true;

    return OK;
}

status_t AMRNBDecoder::stop() {
    CHECK(mStarted);

    if (mInputBuffer) {
        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    delete mBufferGroup;
    mBufferGroup = NULL;

    GSMDecodeFrameExit(&mState);

    mSource->stop();

    mStarted = false;

    return OK;
}

sp<MetaData> AMRNBDecoder::getFormat() {
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

    meta->setCString(kKeyDecoderComponent, "AMRNBDecoder");

    return meta;
}

status_t AMRNBDecoder::read(
        MediaBuffer **out, const ReadOptions *options) {
    status_t err;

    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
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

    size_t numBytesRead =
        AMRDecode(mState,
          (Frame_Type_3GPP)((inputPtr[0] >> 3) & 0x0f),
          (UWord8 *)&inputPtr[1],
          static_cast<int16_t *>(buffer->data()),
          MIME_IETF);

    ++numBytesRead;  // Include the frame type header byte.

    buffer->set_range(0, kNumSamplesPerFrame * sizeof(int16_t));

    if (numBytesRead > mInputBuffer->range_length()) {
        // This is bad, should never have happened, but did. Abort now.

        buffer->release();
        buffer = NULL;

        return ERROR_MALFORMED;
    }

    mInputBuffer->set_range(
            mInputBuffer->range_offset() + numBytesRead,
            mInputBuffer->range_length() - numBytesRead);

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
