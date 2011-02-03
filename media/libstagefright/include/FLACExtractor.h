/*
 * Copyright (C) 2011 The Android Open Source Project
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

#ifndef FLAC_EXTRACTOR_H_
#define FLAC_EXTRACTOR_H_

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaExtractor.h>
#include <utils/String8.h>

namespace android {

class FLACParser;

class FLACExtractor : public MediaExtractor {

public:
    // Extractor assumes ownership of source
    FLACExtractor(const sp<DataSource> &source);

    virtual size_t countTracks();
    virtual sp<MediaSource> getTrack(size_t index);
    virtual sp<MetaData> getTrackMetaData(size_t index, uint32_t flags);

    virtual sp<MetaData> getMetaData();

protected:
    virtual ~FLACExtractor();

private:
    sp<DataSource> mDataSource;
    sp<FLACParser> mParser;
    status_t mInitCheck;
    sp<MetaData> mFileMetadata;

    // There is only one track
    sp<MetaData> mTrackMetadata;

    status_t init();

    FLACExtractor(const FLACExtractor &);
    FLACExtractor &operator=(const FLACExtractor &);

};

bool SniffFLAC(const sp<DataSource> &source, String8 *mimeType,
        float *confidence, sp<AMessage> *);

}  // namespace android

#endif  // FLAC_EXTRACTOR_H_
