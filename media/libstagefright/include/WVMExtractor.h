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

#ifndef WVM_EXTRACTOR_H_

#define WVM_EXTRACTOR_H_

#include <media/stagefright/MediaExtractor.h>

namespace android {

class DataSource;

class WVMExtractor : public MediaExtractor {
public:
    WVMExtractor(const sp<DataSource> &source);

    virtual size_t countTracks();
    virtual sp<MediaSource> getTrack(size_t index);
    virtual sp<MetaData> getTrackMetaData(size_t index, uint32_t flags);
    virtual sp<MetaData> getMetaData();

protected:
    virtual ~WVMExtractor();

private:
    sp<DataSource> mDataSource;
    sp<MediaExtractor> mImpl;

    WVMExtractor(const WVMExtractor &);
    WVMExtractor &operator=(const WVMExtractor &);

};

}  // namespace android

#endif  // DRM_EXTRACTOR_H_

