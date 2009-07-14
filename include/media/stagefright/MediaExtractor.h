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

#ifndef MEDIA_EXTRACTOR_H_

#define MEDIA_EXTRACTOR_H_

#include <utils/RefBase.h>

namespace android {

class DataSource;
class MediaSource;
class MetaData;

class MediaExtractor {
public:
    static MediaExtractor *Create(DataSource *source, const char *mime = NULL);

    virtual ~MediaExtractor() {}

    virtual status_t countTracks(int *num_tracks) = 0;
    virtual status_t getTrack(int index, MediaSource **source) = 0;
    virtual sp<MetaData> getTrackMetaData(int index) = 0;

protected:
    MediaExtractor() {}

private:
    MediaExtractor(const MediaExtractor &);
    MediaExtractor &operator=(const MediaExtractor &);
};

}  // namespace android

#endif  // MEDIA_EXTRACTOR_H_
