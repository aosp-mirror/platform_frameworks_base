/*
 * Copyright (C) 2012 The Android Open Source Project
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

#ifndef TIMED_TEXT_3GPP_SOURCE_H_
#define TIMED_TEXT_3GPP_SOURCE_H_

#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaSource.h>

#include "TimedTextSource.h"

namespace android {

class MediaBuffer;
class Parcel;

class TimedText3GPPSource : public TimedTextSource {
public:
    TimedText3GPPSource(const sp<MediaSource>& mediaSource);
    virtual status_t start() { return mSource->start(); }
    virtual status_t stop() { return mSource->stop(); }
    virtual status_t read(
            int64_t *startTimeUs,
            int64_t *endTimeUs,
            Parcel *parcel,
            const MediaSource::ReadOptions *options = NULL);
    virtual status_t extractGlobalDescriptions(Parcel *parcel);
    virtual sp<MetaData> getFormat();

protected:
    virtual ~TimedText3GPPSource();

private:
    sp<MediaSource> mSource;

    status_t extractAndAppendLocalDescriptions(
            int64_t timeUs, const MediaBuffer *textBuffer, Parcel *parcel);

    DISALLOW_EVIL_CONSTRUCTORS(TimedText3GPPSource);
};

}  // namespace android

#endif  // TIMED_TEXT_3GPP_SOURCE_H_
