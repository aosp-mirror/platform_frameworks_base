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

#ifndef LIVE_DATA_SOURCE_H_

#define LIVE_DATA_SOURCE_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/DataSource.h>
#include <utils/threads.h>
#include <utils/List.h>

namespace android {

struct ABuffer;

struct LiveDataSource : public DataSource {
    LiveDataSource();

    virtual status_t initCheck() const;

    virtual ssize_t readAt(off64_t offset, void *data, size_t size);
    ssize_t readAtNonBlocking(off64_t offset, void *data, size_t size);

    void queueBuffer(const sp<ABuffer> &buffer);
    void queueEOS(status_t finalResult);
    void reset();

    size_t countQueuedBuffers();

protected:
    virtual ~LiveDataSource();

private:
    Mutex mLock;
    Condition mCondition;

    off64_t mOffset;
    List<sp<ABuffer> > mBufferQueue;
    status_t mFinalResult;

    FILE *mBackupFile;

    ssize_t readAt_l(off64_t offset, void *data, size_t size);

    DISALLOW_EVIL_CONSTRUCTORS(LiveDataSource);
};

}  // namespace android

#endif  // LIVE_DATA_SOURCE_H_
