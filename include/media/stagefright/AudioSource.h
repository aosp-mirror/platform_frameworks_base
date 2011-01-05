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

#ifndef AUDIO_SOURCE_H_

#define AUDIO_SOURCE_H_

#include <media/AudioSystem.h>
#include <media/stagefright/MediaSource.h>

namespace android {

class AudioRecord;
struct MediaBufferGroup;

struct AudioSource : public MediaSource {
    // Note that the "channels" parameter is _not_ the number of channels,
    // but a bitmask of AudioSystem::audio_channels constants.
    AudioSource(
            int inputSource, uint32_t sampleRate,
            uint32_t channels = AudioSystem::CHANNEL_IN_MONO);

    status_t initCheck() const;

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();

    // Returns the maximum amplitude since last call.
    int16_t getMaxAmplitude();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~AudioSource();

private:
    enum {
        kMaxBufferSize = 2048,

        // After the initial mute, we raise the volume linearly
        // over kAutoRampDurationUs.
        kAutoRampDurationUs = 700000,

        // This is the initial mute duration to suppress
        // the video recording signal tone
        kAutoRampStartUs = 1000000,
      };

    AudioRecord *mRecord;
    status_t mInitCheck;
    bool mStarted;

    bool mCollectStats;
    bool mTrackMaxAmplitude;
    int64_t mStartTimeUs;
    int16_t mMaxAmplitude;
    int64_t mPrevSampleTimeUs;
    int64_t mTotalLostFrames;
    int64_t mPrevLostBytes;
    int64_t mInitialReadTimeUs;

    MediaBufferGroup *mGroup;

    void trackMaxAmplitude(int16_t *data, int nSamples);

    // This is used to raise the volume from mute to the
    // actual level linearly.
    void rampVolume(
        int32_t startFrame, int32_t rampDurationFrames,
        uint8_t *data,   size_t bytes);

    AudioSource(const AudioSource &);
    AudioSource &operator=(const AudioSource &);
};

}  // namespace android

#endif  // AUDIO_SOURCE_H_
