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
#define LOG_TAG "G711Decoder"
#include <utils/Log.h>

#include "G711Decoder.h"

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>

static const size_t kMaxNumSamplesPerFrame = 16384;

namespace android {

G711Decoder::G711Decoder(const sp<MediaSource> &source)
    : mSource(source),
      mStarted(false),
      mBufferGroup(NULL) {
}

G711Decoder::~G711Decoder() {
    if (mStarted) {
        stop();
    }
}

status_t G711Decoder::start(MetaData *params) {
    CHECK(!mStarted);

    const char *mime;
    CHECK(mSource->getFormat()->findCString(kKeyMIMEType, &mime));

    mIsMLaw = false;
    if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_G711_MLAW)) {
        mIsMLaw = true;
    } else if (strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_G711_ALAW)) {
        return ERROR_UNSUPPORTED;
    }

    mBufferGroup = new MediaBufferGroup;
    mBufferGroup->add_buffer(
            new MediaBuffer(kMaxNumSamplesPerFrame * sizeof(int16_t)));

    mSource->start();

    mStarted = true;

    return OK;
}

status_t G711Decoder::stop() {
    CHECK(mStarted);

    delete mBufferGroup;
    mBufferGroup = NULL;

    mSource->stop();

    mStarted = false;

    return OK;
}

sp<MetaData> G711Decoder::getFormat() {
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

    meta->setCString(kKeyDecoderComponent, "G711Decoder");

    return meta;
}

status_t G711Decoder::read(
        MediaBuffer **out, const ReadOptions *options) {
    status_t err;

    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    if (options && options->getSeekTo(&seekTimeUs, &mode)) {
        CHECK(seekTimeUs >= 0);
    } else {
        seekTimeUs = -1;
    }

    MediaBuffer *inBuffer;
    err = mSource->read(&inBuffer, options);

    if (err != OK) {
        return err;
    }

    if (inBuffer->range_length() > kMaxNumSamplesPerFrame) {
        LOGE("input buffer too large (%d).", inBuffer->range_length());

        inBuffer->release();
        inBuffer = NULL;

        return ERROR_UNSUPPORTED;
    }

    int64_t timeUs;
    CHECK(inBuffer->meta_data()->findInt64(kKeyTime, &timeUs));

    const uint8_t *inputPtr =
        (const uint8_t *)inBuffer->data() + inBuffer->range_offset();

    MediaBuffer *outBuffer;
    CHECK_EQ(mBufferGroup->acquire_buffer(&outBuffer), OK);

    if (mIsMLaw) {
        DecodeMLaw(
                static_cast<int16_t *>(outBuffer->data()),
                inputPtr, inBuffer->range_length());
    } else {
        DecodeALaw(
                static_cast<int16_t *>(outBuffer->data()),
                inputPtr, inBuffer->range_length());
    }

    // Each 8-bit byte is converted into a 16-bit sample.
    outBuffer->set_range(0, inBuffer->range_length() * 2);

    outBuffer->meta_data()->setInt64(kKeyTime, timeUs);

    inBuffer->release();
    inBuffer = NULL;

    *out = outBuffer;

    return OK;
}

// static
void G711Decoder::DecodeALaw(
        int16_t *out, const uint8_t *in, size_t inSize) {
    while (inSize-- > 0) {
        int32_t x = *in++;

        int32_t ix = x ^ 0x55;
        ix &= 0x7f;

        int32_t iexp = ix >> 4;
        int32_t mant = ix & 0x0f;

        if (iexp > 0) {
            mant += 16;
        }

        mant = (mant << 4) + 8;

        if (iexp > 1) {
            mant = mant << (iexp - 1);
        }

        *out++ = (x > 127) ? mant : -mant;
    }
}

// static
void G711Decoder::DecodeMLaw(
        int16_t *out, const uint8_t *in, size_t inSize) {
    while (inSize-- > 0) {
        int32_t x = *in++;

        int32_t mantissa = ~x;
        int32_t exponent = (mantissa >> 4) & 7;
        int32_t segment = exponent + 1;
        mantissa &= 0x0f;

        int32_t step = 4 << segment;

        int32_t abs = (0x80l << exponent) + step * mantissa + step / 2 - 4 * 33;

        *out++ = (x < 0x80) ? -abs : abs;
    }
}

}  // namespace android
