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

#ifndef MP3_EXTRACTOR_H_

#define MP3_EXTRACTOR_H_

#include <media/stagefright/MediaExtractor.h>

namespace android {

class DataSource;
class String8;

class MP3Extractor : public MediaExtractor {
public:
    // Extractor assumes ownership of "source".
    MP3Extractor(const sp<DataSource> &source);

    virtual size_t countTracks();
    virtual sp<MediaSource> getTrack(size_t index);
    virtual sp<MetaData> getTrackMetaData(size_t index);

protected:
    virtual ~MP3Extractor();

private:
    sp<DataSource> mDataSource;
    off_t mFirstFramePos;
    sp<MetaData> mMeta;
    uint32_t mFixedHeader;

    MP3Extractor(const MP3Extractor &);
    MP3Extractor &operator=(const MP3Extractor &);
};

bool SniffMP3(
        const sp<DataSource> &source, String8 *mimeType, float *confidence);

}  // namespace android

#endif  // MP3_EXTRACTOR_H_
