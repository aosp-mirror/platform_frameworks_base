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

#ifndef WVMMEDIA_SOURCE_H_
#define WVMMEDIA_SOURCE_H_

#include "AndroidConfig.h"
#include "WVStreamControlAPI.h"
#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <media/stagefright/MediaBufferGroup.h>
#include <utils/RefBase.h>


namespace android {

class WVMFileSource;

class WVMMediaSource : public MediaSource {
public:
    WVMMediaSource(WVSession *session, WVEsSelector esSelector,
                   const sp<MetaData> &metaData);

    void delegateFileSource(sp<WVMFileSource> fileSource);
    void delegateDataSource(sp<DataSource> dataSource);

    virtual status_t start(MetaData *params = NULL);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(MediaBuffer **buffer, const ReadOptions *options = NULL);

protected:
    virtual ~WVMMediaSource();

private:
    Mutex mLock;

    WVSession *mSession;
    WVEsSelector mESSelector;  // indicates audio vs. video

    sp<MetaData> mTrackMetaData;

    bool mStarted;

    MediaBufferGroup *mGroup;

    unsigned long long mDts;
    unsigned long long mPts;

    sp<WVMFileSource> mFileSource;
    sp<DataSource> mDataSource;

    void allocBufferGroup();

    WVMMediaSource(const WVMMediaSource &);
    WVMMediaSource &operator=(const WVMMediaSource &);
};

}  // namespace android

#endif // WVMMEDIA_SOURCE_H_
