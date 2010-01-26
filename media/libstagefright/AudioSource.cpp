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

#include <media/stagefright/AudioSource.h>

#include <media/AudioRecord.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <media/stagefright/MediaDebug.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>

namespace android {

AudioSource::AudioSource(
        int inputSource, uint32_t sampleRate, uint32_t channels)
    : mRecord(new AudioRecord(
                inputSource, sampleRate, AudioSystem::PCM_16_BIT, channels)),
      mInitCheck(mRecord->initCheck()),
      mStarted(false),
      mGroup(NULL) {
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

    MediaBuffer *buffer;
    CHECK_EQ(mGroup->acquire_buffer(&buffer), OK);

    uint32_t numFramesRecorded;
    mRecord->getPosition(&numFramesRecorded);

    buffer->meta_data()->setInt64(
            kKeyTime,
            (1000000ll * numFramesRecorded) / mRecord->getSampleRate()
            - mRecord->latency() * 1000);

    ssize_t n = mRecord->read(buffer->data(), buffer->size());

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
