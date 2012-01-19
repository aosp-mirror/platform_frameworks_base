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

#ifndef THROTTLED_SOURCE_H_

#define THROTTLED_SOURCE_H_

#include <media/stagefright/DataSource.h>
#include <utils/threads.h>

namespace android {

struct ThrottledSource : public DataSource {
    ThrottledSource(
            const sp<DataSource> &source,
            int32_t bandwidthLimitBytesPerSecond);

    virtual status_t initCheck() const;

    virtual ssize_t readAt(off64_t offset, void *data, size_t size);

    virtual status_t getSize(off64_t *size);
    virtual uint32_t flags();

    virtual String8 getMIMEType() const {
        return mSource->getMIMEType();
    }


private:
    Mutex mLock;

    sp<DataSource> mSource;
    int32_t mBandwidthLimitBytesPerSecond;
    int64_t mStartTimeUs;
    size_t mTotalTransferred;

    ThrottledSource(const ThrottledSource &);
    ThrottledSource &operator=(const ThrottledSource &);
};

}  // namespace android

#endif  // THROTTLED_SOURCE_H_
