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

#undef NDEBUG
#include <assert.h>

#define LOG_TAG "AudioPlayer"
#include <utils/Log.h>

#include <media/AudioTrack.h>
#include <media/stagefright/AudioPlayer.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>

namespace android {

AudioPlayer::AudioPlayer(const sp<MediaPlayerBase::AudioSink> &audioSink)
    : mSource(NULL),
      mAudioTrack(NULL),
      mInputBuffer(NULL),
      mSampleRate(0),
      mLatencyUs(0),
      mFrameSize(0),
      mNumFramesPlayed(0),
      mPositionTimeMediaUs(-1),
      mPositionTimeRealUs(-1),
      mSeeking(false),
      mStarted(false),
      mAudioSink(audioSink) {
}

AudioPlayer::~AudioPlayer() {
    if (mStarted) {
        stop();
    }
}

void AudioPlayer::setSource(MediaSource *source) {
    assert(mSource == NULL);
    mSource = source;
}

void AudioPlayer::start() {
    assert(!mStarted);
    assert(mSource != NULL);

    status_t err = mSource->start();
    assert(err == OK);

    sp<MetaData> format = mSource->getFormat();
    const char *mime;
    bool success = format->findCString(kKeyMIMEType, &mime);
    assert(success);
    assert(!strcasecmp(mime, "audio/raw"));

    success = format->findInt32(kKeySampleRate, &mSampleRate);
    assert(success);

    int32_t numChannels;
    success = format->findInt32(kKeyChannelCount, &numChannels);
    assert(success);

    if (mAudioSink.get() != NULL) {
        status_t err = mAudioSink->open(
                mSampleRate, numChannels, AudioSystem::PCM_16_BIT,
                DEFAULT_AUDIOSINK_BUFFERCOUNT,
                &AudioPlayer::AudioSinkCallback, this);
        assert(err == OK);

        mLatencyUs = (int64_t)mAudioSink->latency() * 1000;
        mFrameSize = mAudioSink->frameSize();

        mAudioSink->start();
    } else {
        mAudioTrack = new AudioTrack(
                AudioSystem::MUSIC, mSampleRate, AudioSystem::PCM_16_BIT,
                (numChannels == 2)
                    ? AudioSystem::CHANNEL_OUT_STEREO
                    : AudioSystem::CHANNEL_OUT_MONO,
                8192, 0, &AudioCallback, this, 0);

        assert(mAudioTrack->initCheck() == OK);

        mLatencyUs = (int64_t)mAudioTrack->latency() * 1000;
        mFrameSize = mAudioTrack->frameSize();

        mAudioTrack->start();
    }

    mStarted = true;
}

void AudioPlayer::pause() {
    assert(mStarted);

    if (mAudioSink.get() != NULL) {
        mAudioSink->pause();
    } else {
        mAudioTrack->stop();
    }
}

void AudioPlayer::resume() {
    assert(mStarted);

    if (mAudioSink.get() != NULL) {
        mAudioSink->start();
    } else {
        mAudioTrack->start();
    }
}

void AudioPlayer::stop() {
    assert(mStarted);

    if (mAudioSink.get() != NULL) {
        mAudioSink->stop();
    } else {
        mAudioTrack->stop();

        delete mAudioTrack;
        mAudioTrack = NULL;
    }
    
    // Make sure to release any buffer we hold onto so that the
    // source is able to stop().
    if (mInputBuffer != NULL) {
        LOGI("AudioPlayer releasing input buffer.");

        mInputBuffer->release();
        mInputBuffer = NULL;
    }

    mSource->stop();
    
    mNumFramesPlayed = 0;
    mPositionTimeMediaUs = -1;
    mPositionTimeRealUs = -1;
    mSeeking = false;
    mStarted = false;
}

// static
void AudioPlayer::AudioCallback(int event, void *user, void *info) {
    static_cast<AudioPlayer *>(user)->AudioCallback(event, info);
}

// static
void AudioPlayer::AudioSinkCallback(
        MediaPlayerBase::AudioSink *audioSink,
        void *buffer, size_t size, void *cookie) {
    AudioPlayer *me = (AudioPlayer *)cookie;

    me->fillBuffer(buffer, size);
}

void AudioPlayer::AudioCallback(int event, void *info) {
    if (event != AudioTrack::EVENT_MORE_DATA) {
        return;
    }

    AudioTrack::Buffer *buffer = (AudioTrack::Buffer *)info;
    fillBuffer(buffer->raw, buffer->size);
}

void AudioPlayer::fillBuffer(void *data, size_t size) {
    if (mNumFramesPlayed == 0) {
        LOGI("AudioCallback");
    }

    size_t size_done = 0;
    size_t size_remaining = size;
    while (size_remaining > 0) {
        MediaSource::ReadOptions options;

        {
            Mutex::Autolock autoLock(mLock);

            if (mSeeking) {
                options.setSeekTo(mSeekTimeUs);

                if (mInputBuffer != NULL) {
                    mInputBuffer->release();
                    mInputBuffer = NULL;
                }
                mSeeking = false;
            }
        }

        if (mInputBuffer == NULL) {
            status_t err = mSource->read(&mInputBuffer, &options);

            assert((err == OK && mInputBuffer != NULL)
                   || (err != OK && mInputBuffer == NULL));

            if (err != OK) {
                memset((char *)data + size_done, 0, size_remaining);
                break;
            }

            int32_t units, scale;
            bool success =
                mInputBuffer->meta_data()->findInt32(kKeyTimeUnits, &units);
            success = success &&
                mInputBuffer->meta_data()->findInt32(kKeyTimeScale, &scale);
            assert(success);

            Mutex::Autolock autoLock(mLock);
            mPositionTimeMediaUs = (int64_t)units * 1000000 / scale;

            mPositionTimeRealUs =
                ((mNumFramesPlayed + size_done / mFrameSize) * 1000000)
                    / mSampleRate;
        }

        if (mInputBuffer->range_length() == 0) {
            mInputBuffer->release();
            mInputBuffer = NULL;

            continue;
        }

        size_t copy = size_remaining;
        if (copy > mInputBuffer->range_length()) {
            copy = mInputBuffer->range_length();
        }

        memcpy((char *)data + size_done,
               (const char *)mInputBuffer->data() + mInputBuffer->range_offset(),
               copy);

        mInputBuffer->set_range(mInputBuffer->range_offset() + copy,
                                mInputBuffer->range_length() - copy);
                    
        size_done += copy;
        size_remaining -= copy;
    }

    Mutex::Autolock autoLock(mLock);
    mNumFramesPlayed += size / mFrameSize;
}

int64_t AudioPlayer::getRealTimeUs() {
    Mutex::Autolock autoLock(mLock);
    return getRealTimeUsLocked();
}

int64_t AudioPlayer::getRealTimeUsLocked() const {
    return -mLatencyUs + (mNumFramesPlayed * 1000000) / mSampleRate;
}

int64_t AudioPlayer::getMediaTimeUs() {
    Mutex::Autolock autoLock(mLock);

    return mPositionTimeMediaUs + (getRealTimeUsLocked() - mPositionTimeRealUs);
}

bool AudioPlayer::getMediaTimeMapping(
        int64_t *realtime_us, int64_t *mediatime_us) {
    Mutex::Autolock autoLock(mLock);

    *realtime_us = mPositionTimeRealUs;
    *mediatime_us = mPositionTimeMediaUs;

    return mPositionTimeRealUs != -1 || mPositionTimeMediaUs != -1;
}

status_t AudioPlayer::seekTo(int64_t time_us) {
    Mutex::Autolock autoLock(mLock);

    mSeeking = true;
    mSeekTimeUs = time_us;

    return OK;
}

}
