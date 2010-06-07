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

#ifndef LIVE_SOURCE_H_

#define LIVE_SOURCE_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/AString.h>
#include <media/stagefright/DataSource.h>
#include <utils/Vector.h>

namespace android {

struct ABuffer;
struct HTTPDataSource;
struct M3UParser;

struct LiveSource : public DataSource {
    LiveSource(const char *url);

    virtual status_t initCheck() const;

    virtual ssize_t readAt(off_t offset, void *data, size_t size);

    virtual uint32_t flags() {
        return kWantsPrefetching;
    }

protected:
    virtual ~LiveSource();

private:
    AString mURL;
    status_t mInitCheck;

    sp<M3UParser> mPlaylist;
    int32_t mFirstItemSequenceNumber;
    size_t mPlaylistIndex;
    int64_t mLastFetchTimeUs;

    sp<HTTPDataSource> mSource;
    off_t mSourceSize;
    off_t mOffsetBias;

    status_t fetchM3U(const char *url, sp<ABuffer> *buffer);

    bool switchToNext();
    bool loadPlaylist();

    DISALLOW_EVIL_CONSTRUCTORS(LiveSource);
};

}  // namespace android

#endif  // LIVE_SOURCE_H_
