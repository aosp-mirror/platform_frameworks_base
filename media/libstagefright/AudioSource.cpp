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
#include <stdlib.h>

namespace android {

AudioSource::AudioSource(
        int inputSource, uint32_t sampleRate, uint32_t channels)
    : mStarted(false),
      mCollectStats(false),
      mPrevSampleTimeUs(0),
      mTotalLostFrames(0),
      mPrevLostBytes(0),
      mGroup(NULL) {

    LOGV("sampleRate: %d, channels: %d", sampleRate, channels);
    CHECK(channels == 1 || channels == 2);
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

    if (mInitCheck != OK) {
        return NO_INIT;
    }

    char value[PROPERTY_VALUE_MAX];
    if (property_get("media.stagefright.record-stats", value, NULL)
        && (!strcmp(value, "1") || !strcasecmp(value, "true"))) {
        mCollectStats = true;
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

    if (mInitCheck != OK) {
        return NO_INIT;
    }

    mRecord->stop();

    delete mGroup;
    mGroup = NULL;

    mStarted = false;

    if (mCollectStats) {
        LOGI("Total lost audio frames: %lld",
            mTotalLostFrames + (mPrevLostBytes >> 1));
    }

    return OK;
}

sp<MetaData> AudioSource::getFormat() {
    if (mInitCheck != OK) {
        return 0;
    }

    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_RAW);
    meta->setInt32(kKeySampleRate, mRecord->getSampleRate());
    meta->setInt32(kKeyChannelCount, mRecord->channelCount());
    meta->setInt32(kKeyMaxInputSize, kMaxBufferSize);

    return meta;
}

/*
 * Returns -1 if frame skipping request is too long.
 * Returns  0 if there is no need to skip frames.
 * Returns  1 if we need to skip frames.
 */
static int skipFrame(int64_t timestampUs,
        const MediaSource::ReadOptions *options) {

    int64_t skipFrameUs;
    if (!options || !options->getSkipFrame(&skipFrameUs)) {
        return 0;
    }

    if (skipFrameUs <= timestampUs) {
        return 0;
    }

    // Safe guard against the abuse of the kSkipFrame_Option.
    if (skipFrameUs - timestampUs >= 1E6) {
        LOGE("Frame skipping requested is way too long: %lld us",
            skipFrameUs - timestampUs);

        return -1;
    }

    LOGV("skipFrame: %lld us > timestamp: %lld us",
        skipFrameUs, timestampUs);

    return 1;

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

    if (mInitCheck != OK) {
        return NO_INIT;
    }

    int64_t readTimeUs = systemTime() / 1000;
    *out = NULL;

    MediaBuffer *buffer;
    CHECK_EQ(mGroup->acquire_buffer(&buffer), OK);

    int err = 0;
    while (mStarted) {

        uint32_t numFramesRecorded;
        mRecord->getPosition(&numFramesRecorded);


        if (numFramesRecorded == 0 && mPrevSampleTimeUs == 0) {
            mInitialReadTimeUs = readTimeUs;
            // Initial delay
            if (mStartTimeUs > 0) {
                mStartTimeUs = readTimeUs - mStartTimeUs;
            } else {
                // Assume latency is constant.
                mStartTimeUs += mRecord->latency() * 1000;
            }
            mPrevSampleTimeUs = mStartTimeUs;
        }

        uint32_t sampleRate = mRecord->getSampleRate();

        // Insert null frames when lost frames are detected.
        int64_t timestampUs = mPrevSampleTimeUs;
        uint32_t numLostBytes = mRecord->getInputFramesLost() << 1;
        numLostBytes += mPrevLostBytes;
#if 0
        // Simulate lost frames
        numLostBytes = ((rand() * 1.0 / RAND_MAX)) * 2 * kMaxBufferSize;
        numLostBytes &= 0xFFFFFFFE; // Alignment requirement

        // Reduce the chance to lose
        if (rand() * 1.0 / RAND_MAX >= 0.05) {
            numLostBytes = 0;
        }
#endif
        if (numLostBytes > 0) {
            if (numLostBytes > kMaxBufferSize) {
                mPrevLostBytes = numLostBytes - kMaxBufferSize;
                numLostBytes = kMaxBufferSize;
            } else {
                mPrevLostBytes = 0;
            }

            CHECK_EQ(numLostBytes & 1, 0);
            timestampUs += ((1000000LL * (numLostBytes >> 1)) +
                    (sampleRate >> 1)) / sampleRate;

            CHECK(timestampUs > mPrevSampleTimeUs);
            if (mCollectStats) {
                mTotalLostFrames += (numLostBytes >> 1);
            }
            if ((err = skipFrame(timestampUs, options)) == -1) {
                buffer->release();
                return UNKNOWN_ERROR;
            } else if (err != 0) {
                continue;
            }
            memset(buffer->data(), 0, numLostBytes);
            buffer->set_range(0, numLostBytes);
            if (numFramesRecorded == 0) {
                buffer->meta_data()->setInt64(kKeyAnchorTime, mStartTimeUs);
            }
            buffer->meta_data()->setInt64(kKeyTime, mStartTimeUs + mPrevSampleTimeUs);
            buffer->meta_data()->setInt64(kKeyDriftTime, readTimeUs - mInitialReadTimeUs);
            mPrevSampleTimeUs = timestampUs;
            *out = buffer;
            return OK;
        }

        ssize_t n = mRecord->read(buffer->data(), buffer->size());
        if (n <= 0) {
            LOGE("Read from AudioRecord returns: %ld", n);
            buffer->release();
            return UNKNOWN_ERROR;
        }

        int64_t recordDurationUs = (1000000LL * n >> 1) / sampleRate;
        timestampUs += recordDurationUs;
        if ((err = skipFrame(timestampUs, options)) == -1) {
            buffer->release();
            return UNKNOWN_ERROR;
        } else if (err != 0) {
            continue;
        }

        if (mPrevSampleTimeUs - mStartTimeUs < kAutoRampStartUs) {
            // Mute the initial video recording signal
            memset((uint8_t *) buffer->data(), 0, n);
        } else if (mPrevSampleTimeUs - mStartTimeUs < kAutoRampStartUs + kAutoRampDurationUs) {
            int32_t autoRampDurationFrames =
                    (kAutoRampDurationUs * sampleRate + 500000LL) / 1000000LL;

            int32_t autoRampStartFrames =
                    (kAutoRampStartUs * sampleRate + 500000LL) / 1000000LL;

            int32_t nFrames = numFramesRecorded - autoRampStartFrames;
            rampVolume(nFrames, autoRampDurationFrames, (uint8_t *) buffer->data(), n);
        }
        if (mTrackMaxAmplitude) {
            trackMaxAmplitude((int16_t *) buffer->data(), n >> 1);
        }

        if (numFramesRecorded == 0) {
            buffer->meta_data()->setInt64(kKeyAnchorTime, mStartTimeUs);
        }

        buffer->meta_data()->setInt64(kKeyTime, mStartTimeUs + mPrevSampleTimeUs);
        buffer->meta_data()->setInt64(kKeyDriftTime, readTimeUs - mInitialReadTimeUs);
        CHECK(timestampUs > mPrevSampleTimeUs);
        mPrevSampleTimeUs = timestampUs;
        LOGV("initial delay: %lld, sample rate: %d, timestamp: %lld",
                mStartTimeUs, sampleRate, timestampUs);

        buffer->set_range(0, n);

        *out = buffer;
        return OK;
    }

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
    LOGV("max amplitude since last call: %d", value);
    return value;
}

}  // namespace android
