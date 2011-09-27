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

#ifndef STREAMING_SOURCE_H_

#define STREAMING_SOURCE_H_

#include "NuPlayer.h"
#include "NuPlayerSource.h"

namespace android {

struct ABuffer;
struct ATSParser;

struct NuPlayer::StreamingSource : public NuPlayer::Source {
    StreamingSource(const sp<IStreamSource> &source);

    virtual void start();

    virtual status_t feedMoreTSData();

    virtual sp<MetaData> getFormat(bool audio);
    virtual status_t dequeueAccessUnit(bool audio, sp<ABuffer> *accessUnit);

protected:
    virtual ~StreamingSource();

private:
    sp<IStreamSource> mSource;
    status_t mFinalResult;
    sp<NuPlayerStreamListener> mStreamListener;
    sp<ATSParser> mTSParser;

    DISALLOW_EVIL_CONSTRUCTORS(StreamingSource);
};

}  // namespace android

#endif  // STREAMING_SOURCE_H_
