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

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~AudioSource();

private:
    enum { kMaxBufferSize = 2048 };

    AudioRecord *mRecord;
    status_t mInitCheck;
    bool mStarted;

    bool mCollectStats;
    int64_t mTotalReadTimeUs;
    int64_t mTotalReadBytes;
    int64_t mTotalReads;

    MediaBufferGroup *mGroup;

    AudioSource(const AudioSource &);
    AudioSource &operator=(const AudioSource &);
};

}  // namespace android

#endif  // AUDIO_SOURCE_H_
