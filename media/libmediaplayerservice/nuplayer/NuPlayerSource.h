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

#ifndef NUPLAYER_SOURCE_H_

#define NUPLAYER_SOURCE_H_

#include "NuPlayer.h"

namespace android {

struct ABuffer;

struct NuPlayer::Source : public RefBase {
    Source() {}

    virtual void start() = 0;
    virtual void stop() {}

    // Returns OK iff more data was available,
    // an error or ERROR_END_OF_STREAM if not.
    virtual status_t feedMoreTSData() = 0;

    virtual sp<MetaData> getFormat(bool audio) = 0;

    virtual status_t dequeueAccessUnit(
            bool audio, sp<ABuffer> *accessUnit) = 0;

    virtual status_t getDuration(int64_t *durationUs) {
        return INVALID_OPERATION;
    }

    virtual status_t seekTo(int64_t seekTimeUs) {
        return INVALID_OPERATION;
    }

    virtual bool isSeekable() {
        return false;
    }

protected:
    virtual ~Source() {}

private:
    DISALLOW_EVIL_CONSTRUCTORS(Source);
};

}  // namespace android

#endif  // NUPLAYER_SOURCE_H_

