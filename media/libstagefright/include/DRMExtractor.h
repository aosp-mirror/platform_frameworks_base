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

#ifndef DRM_EXTRACTOR_H_

#define DRM_EXTRACTOR_H_

#include <media/stagefright/MediaExtractor.h>
#include <drm/DrmManagerClient.h>

namespace android {

struct AMessage;
class DataSource;
class SampleTable;
class String8;
class DecryptHandle;

class DRMExtractor : public MediaExtractor {
public:
    DRMExtractor(const sp<DataSource> &source, const char *mime);

    virtual size_t countTracks();
    virtual sp<MediaSource> getTrack(size_t index);
    virtual sp<MetaData> getTrackMetaData(size_t index, uint32_t flags);
    virtual sp<MetaData> getMetaData();

protected:
    virtual ~DRMExtractor();

private:
    sp<DataSource> mDataSource;

    sp<MediaExtractor> mOriginalExtractor;
    sp<DecryptHandle> mDecryptHandle;
    DrmManagerClient* mDrmManagerClient;

    DRMExtractor(const DRMExtractor &);
    DRMExtractor &operator=(const DRMExtractor &);
};

bool SniffDRM(
        const sp<DataSource> &source, String8 *mimeType, float *confidence,
            sp<AMessage> *);

}  // namespace android

#endif  // DRM_EXTRACTOR_H_

