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

#include <media/stagefright/MediaSource.h>

namespace android {

class AudioRecord;

class AudioSource {
public:
    AudioSource(int inputSource);
    virtual ~AudioSource();

    status_t initCheck() const;

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();
    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

private:
    AudioRecord *mRecord;
    status_t mInitCheck;

    AudioSource(const AudioSource &);
    AudioSource &operator=(const AudioSource &);
};

}  // namespace android

#endif  // AUDIO_SOURCE_H_
