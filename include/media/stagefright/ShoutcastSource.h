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

#ifndef SHOUTCAST_SOURCE_H_

#define SHOUTCAST_SOURCE_H_

#include <sys/types.h>

#include <media/stagefright/MediaSource.h>

namespace android {

class HTTPStream;
class MediaBufferGroup;

class ShoutcastSource : public MediaSource {
public:
    // Assumes ownership of "http".
    ShoutcastSource(HTTPStream *http);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~ShoutcastSource();

private:
    HTTPStream *mHttp;
    size_t mMetaDataOffset;
    size_t mBytesUntilMetaData;

    MediaBufferGroup *mGroup;
    bool mStarted;

    ShoutcastSource(const ShoutcastSource &);
    ShoutcastSource &operator= (const ShoutcastSource &);
};

}  // namespace android

#endif  // SHOUTCAST_SOURCE_H_

