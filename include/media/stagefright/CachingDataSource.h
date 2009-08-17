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

#ifndef CACHING_DATASOURCE_H_

#define CACHING_DATASOURCE_H_

#include <media/stagefright/DataSource.h>
#include <media/stagefright/MediaErrors.h>
#include <utils/threads.h>

namespace android {

class CachingDataSource : public DataSource {
public:
    CachingDataSource(
            const sp<DataSource> &source, size_t pageSize, int numPages);

    status_t InitCheck() const;

    virtual ssize_t read_at(off_t offset, void *data, size_t size);

protected:
    virtual ~CachingDataSource();

private:
    struct Page {
        Page *mPrev, *mNext;
        off_t mOffset;
        size_t mLength;
        void *mData;
    };

    sp<DataSource> mSource;
    void *mData;
    size_t mPageSize;
    Page *mFirst, *mLast;

    Page *allocate_page();

    Mutex mLock;

    CachingDataSource(const CachingDataSource &);
    CachingDataSource &operator=(const CachingDataSource &);
};

}  // namespace android

#endif  // CACHING_DATASOURCE_H_
