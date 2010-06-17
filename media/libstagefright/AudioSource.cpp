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
#define LOG_TAG "AudioSource"
#include <utils/Log.h>

#include <media/stagefright/AudioSource.h>

#include <media/AudioRecord.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <cutils/properties.h>
#include <sys/time.h>
#include <time.h>

namespace android {

AudioSource::AudioSource(
        int inputSource, uint32_t sampleRate, uint32_t channels)
    : mStarted(false),
      mCollectStats(false),
      mTotalReadTimeUs(0),
      mTotalReadBytes(0),
      mTotalReads(0),
      mGroup(NULL) {

    LOGV("sampleRate: %d, channels: %d", sampleRate, channels);
    uint32_t flags = AudioRecord::RECORD_AGC_ENABLE |
                     AudioRecord::RECORD_NS_ENABLE  |
                     AudioRecord::RECORD_IIR_ENABLE;

    mRecord = new AudioRecord(
                inputSource, sampleRate, AudioSystem::PCM_16_BIT,
                channels > 1? AudioSystem::CHANNEL_IN_STEREO: AudioSystem::CHANNEL_IN_MONO,
                4 * kMaxBufferSize / sizeof(int16_t), /* Enable ping-pong buffers */
                flags);

    mInitCheck = mRecord->initCheck();
}

AudioSource::~AudioSource() {
    if (mStarted) {
        stop();
    }

    delete mRecord;
    mRecord = NULL;
}

status_t AudioSource::initCheck() const {
    return mInitCheck;
}

status_t AudioSource::start(MetaData *params) {
    if (mStarted) {
        return UNKNOWN_ERROR;
    }

    char value[PROPERTY_VALUE_MAX];
    if (property_get("media.stagefright.record-stats", value, NULL)
        && (!strcmp(value, "1") || !strcasecmp(value, "true"))) {
        mCollectStats = true;
    }
    status_t err = mRecord->start();

    if (err == OK) {
        mGroup = new MediaBufferGroup;
        mGroup->add_buffer(new MediaBuffer(kMaxBufferSize));

        mStarted = true;
    }

    return err;
}

status_t AudioSource::stop() {
    if (!mStarted) {
        return UNKNOWN_ERROR;
    }

    mRecord->stop();

    delete mGroup;
    mGroup = NULL;

    mStarted = false;

    if (mCollectStats) {
        LOGI("%lld reads: %.2f bps in %lld us",
                mTotalReads,
                (mTotalReadBytes * 8000000.0) / mTotalReadTimeUs,
                mTotalReadTimeUs);
    }

    return OK;
}

sp<MetaData> AudioSource::getFormat() {
    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_RAW);
    meta->setInt32(kKeySampleRate, mRecord->getSampleRate());
    meta->setInt32(kKeyChannelCount, mRecord->channelCount());
    meta->setInt32(kKeyMaxInputSize, kMaxBufferSize);

    return meta;
}

status_t AudioSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    *out = NULL;
    ++mTotalReads;

    MediaBuffer *buffer;
    CHECK_EQ(mGroup->acquire_buffer(&buffer), OK);

    uint32_t numFramesRecorded;
    mRecord->getPosition(&numFramesRecorded);
    int64_t latency = mRecord->latency() * 1000;
    uint32_t sampleRate = mRecord->getSampleRate();
    int64_t timestampUs = (1000000LL * numFramesRecorded) / sampleRate - latency;
    LOGV("latency: %lld, sample rate: %d, timestamp: %lld",
            latency, sampleRate, timestampUs);

    buffer->meta_data()->setInt64(kKeyTime, timestampUs);

    ssize_t n = 0;
    if (mCollectStats) {
        struct timeval tv_start, tv_end;
        gettimeofday(&tv_start, NULL);
        n = mRecord->read(buffer->data(), buffer->size());
        gettimeofday(&tv_end, NULL);
        mTotalReadTimeUs += ((1000000LL * (tv_end.tv_sec - tv_start.tv_sec))
                + (tv_end.tv_usec - tv_start.tv_usec));
        if (n >= 0) {
            mTotalReadBytes += n;
        }
    } else {
        n = mRecord->read(buffer->data(), buffer->size());
    }

    if (n < 0) {
        buffer->release();
        buffer = NULL;

        return (status_t)n;
    }

    buffer->set_range(0, n);

    *out = buffer;

    return OK;
}

}  // namespace android
