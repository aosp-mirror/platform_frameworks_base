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

#ifndef HTTP_LIVE_SOURCE_H_

#define HTTP_LIVE_SOURCE_H_

#include "NuPlayer.h"
#include "NuPlayerSource.h"

namespace android {

struct ATSParser;
struct LiveSession;

struct NuPlayer::HTTPLiveSource : public NuPlayer::Source {
    HTTPLiveSource(const char *url);

    void start();

    // Returns true iff more data was available, false on EOS.
    bool feedMoreTSData();

    sp<MetaData> getFormat(bool audio);
    status_t dequeueAccessUnit(bool audio, sp<ABuffer> *accessUnit);

protected:
    virtual ~HTTPLiveSource();

private:
    AString mURL;
    bool mEOS;
    off64_t mOffset;
    sp<ALooper> mLiveLooper;
    sp<LiveSession> mLiveSession;
    sp<ATSParser> mTSParser;

    DISALLOW_EVIL_CONSTRUCTORS(HTTPLiveSource);
};

}  // namespace android

#endif  // HTTP_LIVE_SOURCE_H_
