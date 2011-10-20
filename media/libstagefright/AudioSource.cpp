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

#include <media/AudioRecord.h>
#include <media/stagefright/AudioSource.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/foundation/ADebug.h>
#include <cutils/properties.h>
#include <stdlib.h>

namespace android {

static void AudioRecordCallbackFunction(int event, void *user, void *info) {
    AudioSource *source = (AudioSource *) user;
    switch (event) {
        case AudioRecord::EVENT_MORE_DATA: {
            source->dataCallbackTimestamp(*((AudioRecord::Buffer *) info), systemTime() / 1000);
            break;
        }
        case AudioRecord::EVENT_OVERRUN: {
            LOGW("AudioRecord reported overrun!");
            break;
        }
        default:
            // does nothing
            break;
    }
}

AudioSource::AudioSource(
        int inputSource, uint32_t sampleRate, uint32_t channels)
    : mStarted(false),
      mSampleRate(sampleRate),
      mPrevSampleTimeUs(0),
      mNumFramesReceived(0),
      mNumClientOwnedBuffers(0) {

    ALOGV("sampleRate: %d, channels: %d", sampleRate, channels);
    CHECK(channels == 1 || channels == 2);
    uint32_t flags = AudioRecord::RECORD_AGC_ENABLE |
                     AudioRecord::RECORD_NS_ENABLE  |
                     AudioRecord::RECORD_IIR_ENABLE;
    mRecord = new AudioRecord(
                inputSource, sampleRate, AUDIO_FORMAT_PCM_16_BIT,
                channels > 1? AUDIO_CHANNEL_IN_STEREO: AUDIO_CHANNEL_IN_MONO,
                4 * kMaxBufferSize / sizeof(int16_t), /* Enable ping-pong buffers */
                flags,
                AudioRecordCallbackFunction,
                this);

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
    Mutex::Autolock autoLock(mLock);
    if (mStarted) {
        return UNKNOWN_ERROR;
    }

    if (mInitCheck != OK) {
        return NO_INIT;
    }

    mTrackMaxAmplitude = false;
    mMaxAmplitude = 0;
    mInitialReadTimeUs = 0;
    mStartTimeUs = 0;
    int64_t startTimeUs;
    if (params && params->findInt64(kKeyTime, &startTimeUs)) {
        mStartTimeUs = startTimeUs;
    }
    status_t err = mRecord->start();
    if (err == OK) {
        mStarted = true;
    } else {
        delete mRecord;
        mRecord = NULL;
    }


    return err;
}

void AudioSource::releaseQueuedFrames_l() {
    ALOGV("releaseQueuedFrames_l");
    List<MediaBuffer *>::iterator it;
    while (!mBuffersReceived.empty()) {
        it = mBuffersReceived.begin();
        (*it)->release();
        mBuffersReceived.erase(it);
    }
}

void AudioSource::waitOutstandingEncodingFrames_l() {
    ALOGV("waitOutstandingEncodingFrames_l: %lld", mNumClientOwnedBuffers);
    while (mNumClientOwnedBuffers > 0) {
        mFrameEncodingCompletionCondition.wait(mLock);
    }
}

status_t AudioSource::stop() {
    Mutex::Autolock autoLock(mLock);
    if (!mStarted) {
        return UNKNOWN_ERROR;
    }

    if (mInitCheck != OK) {
        return NO_INIT;
    }

    mStarted = false;
    mRecord->stop();
    waitOutstandingEncodingFrames_l();
    releaseQueuedFrames_l();

    return OK;
}

sp<MetaData> AudioSource::getFormat() {
    Mutex::Autolock autoLock(mLock);
    if (mInitCheck != OK) {
        return 0;
    }

    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_RAW);
    meta->setInt32(kKeySampleRate, mSampleRate);
    meta->setInt32(kKeyChannelCount, mRecord->channelCount());
    meta->setInt32(kKeyMaxInputSize, kMaxBufferSize);

    return meta;
}

void AudioSource::rampVolume(
        int32_t startFrame, int32_t rampDurationFrames,
        uint8_t *data,   size_t bytes) {

    const int32_t kShift = 14;
    int32_t fixedMultiplier = (startFrame << kShift) / rampDurationFrames;
    const int32_t nChannels = mRecord->channelCount();
    int32_t stopFrame = startFrame + bytes / sizeof(int16_t);
    int16_t *frame = (int16_t *) data;
    if (stopFrame > rampDurationFrames) {
        stopFrame = rampDurationFrames;
    }

    while (startFrame < stopFrame) {
        if (nChannels == 1) {  // mono
            frame[0] = (frame[0] * fixedMultiplier) >> kShift;
            ++frame;
            ++startFrame;
        } else {               // stereo
            frame[0] = (frame[0] * fixedMultiplier) >> kShift;
            frame[1] = (frame[1] * fixedMultiplier) >> kShift;
            frame += 2;
            startFrame += 2;
        }

        // Update the multiplier every 4 frames
        if ((startFrame & 3) == 0) {
            fixedMultiplier = (startFrame << kShift) / rampDurationFrames;
        }
    }
}

status_t AudioSource::read(
        MediaBuffer **out, const ReadOptions *options) {
    Mutex::Autolock autoLock(mLock);
    *out = NULL;

    if (mInitCheck != OK) {
        return NO_INIT;
    }

    while (mStarted && mBuffersReceived.empty()) {
        mFrameAvailableCondition.wait(mLock);
    }
    if (!mStarted) {
        return OK;
    }
    MediaBuffer *buffer = *mBuffersReceived.begin();
    mBuffersReceived.erase(mBuffersReceived.begin());
    ++mNumClientOwnedBuffers;
    buffer->setObserver(this);
    buffer->add_ref();

    // Mute/suppress the recording sound
    int64_t timeUs;
    CHECK(buffer->meta_data()->findInt64(kKeyTime, &timeUs));
    int64_t elapsedTimeUs = timeUs - mStartTimeUs;
    if (elapsedTimeUs < kAutoRampStartUs) {
        memset((uint8_t *) buffer->data(), 0, buffer->range_length());
    } else if (elapsedTimeUs < kAutoRampStartUs + kAutoRampDurationUs) {
        int32_t autoRampDurationFrames =
                    (kAutoRampDurationUs * mSampleRate + 500000LL) / 1000000LL;

        int32_t autoRampStartFrames =
                    (kAutoRampStartUs * mSampleRate + 500000LL) / 1000000LL;

        int32_t nFrames = mNumFramesReceived - autoRampStartFrames;
        rampVolume(nFrames, autoRampDurationFrames,
                (uint8_t *) buffer->data(), buffer->range_length());
    }

    // Track the max recording signal amplitude.
    if (mTrackMaxAmplitude) {
        trackMaxAmplitude(
            (int16_t *) buffer->data(), buffer->range_length() >> 1);
    }

    *out = buffer;
    return OK;
}

void AudioSource::signalBufferReturned(MediaBuffer *buffer) {
    ALOGV("signalBufferReturned: %p", buffer->data());
    Mutex::Autolock autoLock(mLock);
    --mNumClientOwnedBuffers;
    buffer->setObserver(0);
    buffer->release();
    mFrameEncodingCompletionCondition.signal();
    return;
}

status_t AudioSource::dataCallbackTimestamp(
        const AudioRecord::Buffer& audioBuffer, int64_t timeUs) {
    ALOGV("dataCallbackTimestamp: %lld us", timeUs);
    Mutex::Autolock autoLock(mLock);
    if (!mStarted) {
        LOGW("Spurious callback from AudioRecord. Drop the audio data.");
        return OK;
    }

    // Drop retrieved and previously lost audio data.
    if (mNumFramesReceived == 0 && timeUs < mStartTimeUs) {
        mRecord->getInputFramesLost();
        ALOGV("Drop audio data at %lld/%lld us", timeUs, mStartTimeUs);
        return OK;
    }

    if (mNumFramesReceived == 0 && mPrevSampleTimeUs == 0) {
        mInitialReadTimeUs = timeUs;
        // Initial delay
        if (mStartTimeUs > 0) {
            mStartTimeUs = timeUs - mStartTimeUs;
        } else {
            // Assume latency is constant.
            mStartTimeUs += mRecord->latency() * 1000;
        }
        mPrevSampleTimeUs = mStartTimeUs;
    }

    int64_t timestampUs = mPrevSampleTimeUs;

    size_t numLostBytes = 0;
    if (mNumFramesReceived > 0) {  // Ignore earlier frame lost
        // getInputFramesLost() returns the number of lost frames.
        // Convert number of frames lost to number of bytes lost.
        numLostBytes = mRecord->getInputFramesLost() * mRecord->frameSize();
    }

    CHECK_EQ(numLostBytes & 1, 0u);
    CHECK_EQ(audioBuffer.size & 1, 0u);
    size_t bufferSize = numLostBytes + audioBuffer.size;
    MediaBuffer *buffer = new MediaBuffer(bufferSize);
    if (numLostBytes > 0) {
        memset(buffer->data(), 0, numLostBytes);
        memcpy((uint8_t *) buffer->data() + numLostBytes,
                    audioBuffer.i16, audioBuffer.size);
    } else {
        if (audioBuffer.size == 0) {
            LOGW("Nothing is available from AudioRecord callback buffer");
            buffer->release();
            return OK;
        }
        memcpy((uint8_t *) buffer->data(),
                audioBuffer.i16, audioBuffer.size);
    }

    buffer->set_range(0, bufferSize);
    timestampUs += ((1000000LL * (bufferSize >> 1)) +
                    (mSampleRate >> 1)) / mSampleRate;

    if (mNumFramesReceived == 0) {
        buffer->meta_data()->setInt64(kKeyAnchorTime, mStartTimeUs);
    }
    buffer->meta_data()->setInt64(kKeyTime, mPrevSampleTimeUs);
    buffer->meta_data()->setInt64(kKeyDriftTime, timeUs - mInitialReadTimeUs);
    mPrevSampleTimeUs = timestampUs;
    mNumFramesReceived += buffer->range_length() / sizeof(int16_t);
    mBuffersReceived.push_back(buffer);
    mFrameAvailableCondition.signal();

    return OK;
}

void AudioSource::trackMaxAmplitude(int16_t *data, int nSamples) {
    for (int i = nSamples; i > 0; --i) {
        int16_t value = *data++;
        if (value < 0) {
            value = -value;
        }
        if (mMaxAmplitude < value) {
            mMaxAmplitude = value;
        }
    }
}

int16_t AudioSource::getMaxAmplitude() {
    // First call activates the tracking.
    if (!mTrackMaxAmplitude) {
        mTrackMaxAmplitude = true;
    }
    int16_t value = mMaxAmplitude;
    mMaxAmplitude = 0;
    ALOGV("max amplitude since last call: %d", value);
    return value;
}

}  // namespace android
