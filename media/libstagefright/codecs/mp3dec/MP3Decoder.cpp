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

#include "MP3Decoder.h"

#include "include/pvmp3decoder_api.h"

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>

namespace android {

MP3Decoder::MP3Decoder(const sp<MediaSource> &source)
    : mSource(source),
      mStarted(false),
      mBufferGroup(NULL),
      mConfig(new tPVMP3DecoderExternal),
      mDecoderBuf(NULL),
      mAnchorTimeUs(0),
      mNumSamplesOutput(0),
      mInputBuffer(NULL) {
}

MP3Decoder::~MP3Decoder() {
    if (mStarted) {
        stop();
    }

    delete mConfig;
    mConfig = NULL;
}

status_t MP3Decoder::start(MetaData *params) {
    CHECK(!mStarted);

    mBufferGroup = new MediaBufferGroup;
    mBufferGroup->add_buffer(new MediaBuffer(4608 * 2));

    mConfig->equalizerType = flat;
    mConfig->crcEnabled = true;

    uint32_t memRequirements = pvmp3_decoderMemRequirements();
    mDecoderBuf = malloc(memRequirements);

    pvmp3_InitDecoder(mConfig, mDecoderBuf);

    mSource->start();

    mAnchorTimeUs = 0;
    mNumSamplesOutput = 0;
    mStarted = true;

    return OK;
}

status_t MP3Decoder::stop() {
    CHECK(mStarted);

    if (mInputBuffer) {
        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    free(mDecoderBuf);
    mDecoderBuf = NULL;

    delete mBufferGroup;
    mBufferGroup = NULL;

    mSource->stop();

    mStarted = false;

    return OK;
}

sp<MetaData> MP3Decoder::getFormat() {
    sp<MetaData> srcFormat = mSource->getFormat();

    int32_t numChannels;
    int32_t sampleRate;
    CHECK(srcFormat->findInt32(kKeyChannelCount, &numChannels));
    CHECK(srcFormat->findInt32(kKeySampleRate, &sampleRate));

    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_RAW);
    meta->setInt32(kKeyChannelCount, numChannels);
    meta->setInt32(kKeySampleRate, sampleRate);

    int64_t durationUs;
    if (srcFormat->findInt64(kKeyDuration, &durationUs)) {
        meta->setInt64(kKeyDuration, durationUs);
    }

    return meta;
}

status_t MP3Decoder::read(
        MediaBuffer **out, const ReadOptions *options) {
    status_t err;

    *out = NULL;

    int64_t seekTimeUs;
    if (options && options->getSeekTo(&seekTimeUs)) {
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

    mConfig->pInputBuffer =
        (uint8_t *)mInputBuffer->data() + mInputBuffer->range_offset();

    mConfig->inputBufferCurrentLength = mInputBuffer->range_length();
    mConfig->inputBufferMaxLength = 0;
    mConfig->inputBufferUsedLength = 0;

    mConfig->outputFrameSize = buffer->size() / sizeof(int16_t);
    mConfig->pOutputBuffer = static_cast<int16_t *>(buffer->data());

    if (pvmp3_framedecoder(mConfig, mDecoderBuf) != NO_DECODING_ERROR) {
        buffer->release();
        buffer = NULL;

        mInputBuffer->release();
        mInputBuffer = NULL;

        return UNKNOWN_ERROR;
    }

    buffer->set_range(
            0, mConfig->outputFrameSize * sizeof(int16_t));

    mInputBuffer->set_range(
            mInputBuffer->range_offset() + mConfig->inputBufferUsedLength,
            mInputBuffer->range_length() - mConfig->inputBufferUsedLength);

    if (mInputBuffer->range_length() == 0) {
        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    buffer->meta_data()->setInt64(
            kKeyTime,
            mAnchorTimeUs
                + (mNumSamplesOutput * 1000000) / mConfig->samplingRate);

    mNumSamplesOutput += mConfig->outputFrameSize / sizeof(int16_t);

    *out = buffer;

    return OK;
}

}  // namespace android
