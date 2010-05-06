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

#ifndef VORBIS_EXTRACTOR_H_

#define VORBIS_EXTRACTOR_H_

#include <media/stagefright/MediaExtractor.h>

struct OggVorbis_File;

namespace android {

class DataSource;
class String8;

struct VorbisDataSource;

struct VorbisExtractor : public MediaExtractor {
    VorbisExtractor(const sp<DataSource> &source);

    virtual size_t countTracks();
    virtual sp<MediaSource> getTrack(size_t index);
    virtual sp<MetaData> getTrackMetaData(size_t index, uint32_t flags);

    virtual sp<MetaData> getMetaData();

    uint32_t flags() const;

protected:
    virtual ~VorbisExtractor();

private:
    sp<DataSource> mDataSource;
    struct OggVorbis_File *mFile;
    struct VorbisDataSource *mVorbisDataSource;
    status_t mInitCheck;
    sp<MetaData> mMeta;

    VorbisExtractor(const VorbisExtractor &);
    VorbisExtractor &operator=(const VorbisExtractor &);
};

bool SniffVorbis(
        const sp<DataSource> &source, String8 *mimeType, float *confidence);

}  // namespace android

#endif  // VORBIS_EXTRACTOR_H_
