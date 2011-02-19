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

#ifndef WVMEXTRACTOR_H_

#define WVMEXTRACTOR_H_

#include "AndroidConfig.h"
#include "WVStreamControlAPI.h"
#include <media/stagefright/MediaExtractor.h>
#include <media/stagefright/DataSource.h>
#include <utils/RefBase.h>


// DLL entry - given a data source, instantiate a WVMExtractor object

namespace android {

MediaExtractor *GetInstance(sp<DataSource> dataSource);


class WVMMediaSource;
class WVMFileSource;

class WVMExtractorImpl : public MediaExtractor {
public:
    WVMExtractorImpl(sp<DataSource> dataSource);

    virtual size_t countTracks();
    virtual sp<MediaSource> getTrack(size_t index);
    virtual sp<MetaData> getTrackMetaData(size_t index, uint32_t flags);

    virtual sp<MetaData> getMetaData();

protected:
    virtual ~WVMExtractorImpl();

private:
    status_t readAVCCMetaData(sp<MetaData> videoMetaData);
    status_t readESDSMetaData(sp<MetaData> audioMetaData);

    sp<WVMMediaSource> mAudioSource;
    sp<WVMMediaSource> mVideoSource;
    sp<MetaData> mFileMetaData;
    sp<WVMFileSource> mFileSource;
    sp<DataSource> mDataSource;

    bool mHaveMetaData;

    WVSession *mSession;

    status_t mSetupStatus;

    status_t readMetaData();

    const static size_t kStreamCacheSize = 10 * 1024 * 1024;

    WVMExtractorImpl(const WVMExtractorImpl &);
    WVMExtractorImpl &operator=(const WVMExtractorImpl &);
};

}  // namespace android

#endif  // WVMEXTRACTOR_H_
