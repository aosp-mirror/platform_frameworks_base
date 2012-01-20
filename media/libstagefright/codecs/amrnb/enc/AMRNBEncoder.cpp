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

#include "AMRNBEncoder.h"

#include "gsmamr_enc.h"

#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>

namespace android {

static const int32_t kNumSamplesPerFrame = 160;
static const int32_t kSampleRate = 8000;

AMRNBEncoder::AMRNBEncoder(const sp<MediaSource> &source, const sp<MetaData> &meta)
    : mSource(source),
      mMeta(meta),
      mStarted(false),
      mBufferGroup(NULL),
      mEncState(NULL),
      mSidState(NULL),
      mAnchorTimeUs(0),
      mNumFramesOutput(0),
      mInputBuffer(NULL),
      mMode(MR475),
      mNumInputSamples(0) {
}

AMRNBEncoder::~AMRNBEncoder() {
    if (mStarted) {
        stop();
    }
}

static Mode PickModeFromBitrate(int32_t bps) {
    if (bps <= 4750) {
        return MR475;
    } else if (bps <= 5150) {
        return MR515;
    } else if (bps <= 5900) {
        return MR59;
    } else if (bps <= 6700) {
        return MR67;
    } else if (bps <= 7400) {
        return MR74;
    } else if (bps <= 7950) {
        return MR795;
    } else if (bps <= 10200) {
        return MR102;
    } else {
        return MR122;
    }
}

status_t AMRNBEncoder::start(MetaData *params) {
    if (mStarted) {
        ALOGW("Call start() when encoder already started");
        return OK;
    }

    mBufferGroup = new MediaBufferGroup;
    mBufferGroup->add_buffer(new MediaBuffer(32));

    CHECK_EQ(AMREncodeInit(
                &mEncState, &mSidState, false /* dtx_enable */),
             0);

    status_t err = mSource->start(params);
    if (err != OK) {
        ALOGE("AudioSource is not available");
        return err;
    }

    mAnchorTimeUs = 0;
    mNumFramesOutput = 0;
    mStarted = true;
    mNumInputSamples = 0;

    int32_t bitrate;
    if (params && params->findInt32(kKeyBitRate, &bitrate)) {
        mMode = PickModeFromBitrate(bitrate);
    } else {
        mMode = MR475;
    }

    return OK;
}

status_t AMRNBEncoder::stop() {
    if (!mStarted) {
        ALOGW("Call stop() when encoder has not started.");
        return OK;
    }

    if (mInputBuffer) {
        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    delete mBufferGroup;
    mBufferGroup = NULL;

    mSource->stop();

    AMREncodeExit(&mEncState, &mSidState);
    mEncState = mSidState = NULL;

    mStarted = false;

    return OK;
}

sp<MetaData> AMRNBEncoder::getFormat() {
    sp<MetaData> srcFormat = mSource->getFormat();

    mMeta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_AMR_NB);

    int64_t durationUs;
    if (srcFormat->findInt64(kKeyDuration, &durationUs)) {
        mMeta->setInt64(kKeyDuration, durationUs);
    }

    mMeta->setCString(kKeyDecoderComponent, "AMRNBEncoder");

    return mMeta;
}

status_t AMRNBEncoder::read(
        MediaBuffer **out, const ReadOptions *options) {
    status_t err;

    *out = NULL;

    int64_t seekTimeUs;
    ReadOptions::SeekMode mode;
    CHECK(options == NULL || !options->getSeekTo(&seekTimeUs, &mode));
    bool readFromSource = false;
    int64_t wallClockTimeUs = -1;

    while (mNumInputSamples < kNumSamplesPerFrame) {
        if (mInputBuffer == NULL) {
            err = mSource->read(&mInputBuffer, options);

            if (err != OK) {
                if (mNumInputSamples == 0) {
                    return ERROR_END_OF_STREAM;
                }
                memset(&mInputFrame[mNumInputSamples],
                       0,
                       sizeof(int16_t)
                            * (kNumSamplesPerFrame - mNumInputSamples));
                mNumInputSamples = kNumSamplesPerFrame;
                break;
            }

            size_t align = mInputBuffer->range_length() % sizeof(int16_t);
            CHECK_EQ(align, 0);
            readFromSource = true;

            int64_t timeUs;
            if (mInputBuffer->meta_data()->findInt64(kKeyDriftTime, &timeUs)) {
                wallClockTimeUs = timeUs;
            }
            if (mInputBuffer->meta_data()->findInt64(kKeyAnchorTime, &timeUs)) {
                mAnchorTimeUs = timeUs;
            }
        } else {
            readFromSource = false;
        }

        size_t copy =
            (kNumSamplesPerFrame - mNumInputSamples) * sizeof(int16_t);

        if (copy > mInputBuffer->range_length()) {
            copy = mInputBuffer->range_length();
        }

        memcpy(&mInputFrame[mNumInputSamples],
               (const uint8_t *)mInputBuffer->data()
                    + mInputBuffer->range_offset(),
               copy);

        mNumInputSamples += copy / sizeof(int16_t);

        mInputBuffer->set_range(
                mInputBuffer->range_offset() + copy,
                mInputBuffer->range_length() - copy);

        if (mInputBuffer->range_length() == 0) {
            mInputBuffer->release();
            mInputBuffer = NULL;
        }
    }

    MediaBuffer *buffer;
    CHECK_EQ(mBufferGroup->acquire_buffer(&buffer), OK);

    uint8_t *outPtr = (uint8_t *)buffer->data();

    Frame_Type_3GPP frameType;
    int res = AMREncode(
            mEncState, mSidState, (Mode)mMode,
            mInputFrame, outPtr, &frameType, AMR_TX_WMF);

    CHECK(res >= 0);
    CHECK((size_t)res < buffer->size());

    // Convert header byte from WMF to IETF format.
    outPtr[0] = ((outPtr[0] << 3) | 4) & 0x7c;

    buffer->set_range(0, res);

    // Each frame of 160 samples is 20ms long.
    int64_t mediaTimeUs = mNumFramesOutput * 20000LL;
    buffer->meta_data()->setInt64(
            kKeyTime, mAnchorTimeUs + mediaTimeUs);

    if (readFromSource && wallClockTimeUs != -1) {
        buffer->meta_data()->setInt64(kKeyDriftTime,
            mediaTimeUs - wallClockTimeUs);
    }

    ++mNumFramesOutput;

    *out = buffer;

    mNumInputSamples = 0;

    return OK;
}

}  // namespace android
