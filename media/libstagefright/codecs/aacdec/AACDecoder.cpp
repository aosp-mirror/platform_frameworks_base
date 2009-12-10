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

#include "AACDecoder.h"

#include "../../include/ESDS.h"

#include "pvmp4audiodecoder_api.h"

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>

namespace android {

AACDecoder::AACDecoder(const sp<MediaSource> &source)
    : mSource(source),
      mStarted(false),
      mBufferGroup(NULL),
      mConfig(new tPVMP4AudioDecoderExternal),
      mDecoderBuf(NULL),
      mAnchorTimeUs(0),
      mNumSamplesOutput(0),
      mInputBuffer(NULL) {
}

AACDecoder::~AACDecoder() {
    if (mStarted) {
        stop();
    }

    delete mConfig;
    mConfig = NULL;
}

status_t AACDecoder::start(MetaData *params) {
    CHECK(!mStarted);

    mBufferGroup = new MediaBufferGroup;
    mBufferGroup->add_buffer(new MediaBuffer(2048 * 2));

    mConfig->outputFormat = OUTPUTFORMAT_16PCM_INTERLEAVED;
    mConfig->aacPlusUpsamplingFactor = 0;
    mConfig->aacPlusEnabled = false;

    int32_t numChannels;
    CHECK(mSource->getFormat()->findInt32(kKeyChannelCount, &numChannels));
    mConfig->desiredChannels = numChannels;

    UInt32 memRequirements = PVMP4AudioDecoderGetMemRequirements();
    mDecoderBuf = malloc(memRequirements);

    CHECK_EQ(PVMP4AudioDecoderInitLibrary(mConfig, mDecoderBuf),
             MP4AUDEC_SUCCESS);

    uint32_t type;
    const void *data;
    size_t size;
    if (mSource->getFormat()->findData(kKeyESDS, &type, &data, &size)) {
        ESDS esds((const char *)data, size);
        CHECK_EQ(esds.InitCheck(), OK);

        const void *codec_specific_data;
        size_t codec_specific_data_size;
        esds.getCodecSpecificInfo(
                &codec_specific_data, &codec_specific_data_size);

        mConfig->pInputBuffer = (UChar *)codec_specific_data;
        mConfig->inputBufferCurrentLength = codec_specific_data_size;
        mConfig->inputBufferMaxLength = 0;
        mConfig->inputBufferUsedLength = 0;
        mConfig->remainderBits = 0;

        mConfig->pOutputBuffer = NULL;
        mConfig->pOutputBuffer_plus = NULL;
        mConfig->repositionFlag = false;

        CHECK_EQ(PVMP4AudioDecoderConfig(mConfig, mDecoderBuf),
                 MP4AUDEC_SUCCESS);
    }

    mSource->start();

    mAnchorTimeUs = 0;
    mNumSamplesOutput = 0;
    mStarted = true;

    return OK;
}

status_t AACDecoder::stop() {
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

sp<MetaData> AACDecoder::getFormat() {
    sp<MetaData> srcFormat = mSource->getFormat();

    int32_t numChannels;
    int32_t sampleRate;
    int64_t durationUs;
    CHECK(srcFormat->findInt32(kKeyChannelCount, &numChannels));
    CHECK(srcFormat->findInt32(kKeySampleRate, &sampleRate));
    CHECK(srcFormat->findInt64(kKeyDuration, &durationUs));

    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_RAW);
    meta->setInt32(kKeyChannelCount, numChannels);
    meta->setInt32(kKeySampleRate, sampleRate);
    meta->setInt64(kKeyDuration, durationUs);

    return meta;
}

status_t AACDecoder::read(
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
        (UChar *)mInputBuffer->data() + mInputBuffer->range_offset();

    mConfig->inputBufferCurrentLength = mInputBuffer->range_length();
    mConfig->inputBufferMaxLength = 0;
    mConfig->inputBufferUsedLength = 0;
    mConfig->remainderBits = 0;

    mConfig->pOutputBuffer = static_cast<Int16 *>(buffer->data());
    mConfig->pOutputBuffer_plus = NULL;
    mConfig->repositionFlag = false;

    CHECK_EQ(PVMP4AudioDecodeFrame(mConfig, mDecoderBuf), MP4AUDEC_SUCCESS);

    buffer->set_range(
            0, mConfig->frameLength * sizeof(int16_t) * mConfig->desiredChannels);

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

    mNumSamplesOutput += mConfig->frameLength;

    *out = buffer;

    return OK;
}

}  // namespace android
