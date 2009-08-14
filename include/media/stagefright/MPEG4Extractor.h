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

#ifndef MPEG4_EXTRACTOR_H_

#define MPEG4_EXTRACTOR_H_

#include <media/stagefright/MediaExtractor.h>

namespace android {

class DataSource;
class SampleTable;
class String8;

class MPEG4Extractor : public MediaExtractor {
public:
    // Extractor assumes ownership of "source".
    MPEG4Extractor(const sp<DataSource> &source);

    size_t countTracks();
    sp<MediaSource> getTrack(size_t index);
    sp<MetaData> getTrackMetaData(size_t index);

protected:
    virtual ~MPEG4Extractor();

private:
    struct Track {
        Track *next;
        sp<MetaData> meta;
        uint32_t timescale;
        sp<SampleTable> sampleTable;
    };

    sp<DataSource> mDataSource;
    bool mHaveMetadata;

    Track *mFirstTrack, *mLastTrack;

    uint32_t mHandlerType;

    status_t readMetaData();
    status_t parseChunk(off_t *offset, int depth);

    MPEG4Extractor(const MPEG4Extractor &);
    MPEG4Extractor &operator=(const MPEG4Extractor &);
};

bool SniffMPEG4(
        const sp<DataSource> &source, String8 *mimeType, float *confidence);

}  // namespace android

#endif  // MPEG4_EXTRACTOR_H_
