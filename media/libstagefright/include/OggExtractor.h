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

#ifndef OGG_EXTRACTOR_H_

#define OGG_EXTRACTOR_H_

#include <utils/Errors.h>
#include <media/stagefright/MediaExtractor.h>

namespace android {

struct AMessage;
class DataSource;
class String8;

struct MyVorbisExtractor;
struct OggSource;

struct OggExtractor : public MediaExtractor {
    OggExtractor(const sp<DataSource> &source);

    virtual size_t countTracks();
    virtual sp<MediaSource> getTrack(size_t index);
    virtual sp<MetaData> getTrackMetaData(size_t index, uint32_t flags);

    virtual sp<MetaData> getMetaData();

protected:
    virtual ~OggExtractor();

private:
    friend struct OggSource;

    sp<DataSource> mDataSource;
    status_t mInitCheck;

    MyVorbisExtractor *mImpl;

    OggExtractor(const OggExtractor &);
    OggExtractor &operator=(const OggExtractor &);
};

bool SniffOgg(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
        sp<AMessage> *);

void parseVorbisComment(
        const sp<MetaData> &fileMeta, const char *comment, size_t commentLength);

}  // namespace android

#endif  // OGG_EXTRACTOR_H_
