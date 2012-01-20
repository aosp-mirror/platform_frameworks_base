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

//#define LOG_NDEBUG 0
#define LOG_TAG "LiveDataSource"
#include <utils/Log.h>

#include "LiveDataSource.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>

#define SAVE_BACKUP     0

namespace android {

LiveDataSource::LiveDataSource()
    : mOffset(0),
      mFinalResult(OK),
      mBackupFile(NULL) {
#if SAVE_BACKUP
    mBackupFile = fopen("/data/misc/backup.ts", "wb");
    CHECK(mBackupFile != NULL);
#endif
}

LiveDataSource::~LiveDataSource() {
    if (mBackupFile != NULL) {
        fclose(mBackupFile);
        mBackupFile = NULL;
    }
}

status_t LiveDataSource::initCheck() const {
    return OK;
}

size_t LiveDataSource::countQueuedBuffers() {
    Mutex::Autolock autoLock(mLock);

    return mBufferQueue.size();
}

ssize_t LiveDataSource::readAtNonBlocking(
        off64_t offset, void *data, size_t size) {
    Mutex::Autolock autoLock(mLock);

    if (offset != mOffset) {
        ALOGE("Attempt at reading non-sequentially from LiveDataSource.");
        return -EPIPE;
    }

    size_t totalAvailable = 0;
    for (List<sp<ABuffer> >::iterator it = mBufferQueue.begin();
         it != mBufferQueue.end(); ++it) {
        sp<ABuffer> buffer = *it;

        totalAvailable += buffer->size();

        if (totalAvailable >= size) {
            break;
        }
    }

    if (totalAvailable < size) {
        return mFinalResult == OK ? -EWOULDBLOCK : mFinalResult;
    }

    return readAt_l(offset, data, size);
}

ssize_t LiveDataSource::readAt(off64_t offset, void *data, size_t size) {
    Mutex::Autolock autoLock(mLock);
    return readAt_l(offset, data, size);
}

ssize_t LiveDataSource::readAt_l(off64_t offset, void *data, size_t size) {
    if (offset != mOffset) {
        ALOGE("Attempt at reading non-sequentially from LiveDataSource.");
        return -EPIPE;
    }

    size_t sizeDone = 0;

    while (sizeDone < size) {
        while (mBufferQueue.empty() && mFinalResult == OK) {
            mCondition.wait(mLock);
        }

        if (mBufferQueue.empty()) {
            if (sizeDone > 0) {
                mOffset += sizeDone;
                return sizeDone;
            }

            return mFinalResult;
        }

        sp<ABuffer> buffer = *mBufferQueue.begin();

        size_t copy = size - sizeDone;

        if (copy > buffer->size()) {
            copy = buffer->size();
        }

        memcpy((uint8_t *)data + sizeDone, buffer->data(), copy);

        sizeDone += copy;

        buffer->setRange(buffer->offset() + copy, buffer->size() - copy);

        if (buffer->size() == 0) {
            mBufferQueue.erase(mBufferQueue.begin());
        }
    }

    mOffset += sizeDone;

    return sizeDone;
}

void LiveDataSource::queueBuffer(const sp<ABuffer> &buffer) {
    Mutex::Autolock autoLock(mLock);

    if (mFinalResult != OK) {
        return;
    }

#if SAVE_BACKUP
    if (mBackupFile != NULL) {
        CHECK_EQ(fwrite(buffer->data(), 1, buffer->size(), mBackupFile),
                 buffer->size());
    }
#endif

    mBufferQueue.push_back(buffer);
    mCondition.broadcast();
}

void LiveDataSource::queueEOS(status_t finalResult) {
    CHECK_NE(finalResult, (status_t)OK);

    Mutex::Autolock autoLock(mLock);

    mFinalResult = finalResult;
    mCondition.broadcast();
}

void LiveDataSource::reset() {
    Mutex::Autolock autoLock(mLock);

    // XXX FIXME: If we've done a partial read and waiting for more buffers,
    // we'll mix old and new data...

    mFinalResult = OK;
    mBufferQueue.clear();
}

}  // namespace android
