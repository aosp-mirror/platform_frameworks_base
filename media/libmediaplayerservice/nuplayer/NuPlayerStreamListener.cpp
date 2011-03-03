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
#define LOG_TAG "NuPlayerStreamListener"
#include <utils/Log.h>

#include "NuPlayerStreamListener.h"

#include <binder/MemoryDealer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaErrors.h>

namespace android {

NuPlayer::NuPlayerStreamListener::NuPlayerStreamListener(
        const sp<IStreamSource> &source,
        ALooper::handler_id id)
    : mSource(source),
      mTargetID(id),
      mEOS(false),
      mSendDataNotification(true) {
    mSource->setListener(this);

    mMemoryDealer = new MemoryDealer(kNumBuffers * kBufferSize);
    for (size_t i = 0; i < kNumBuffers; ++i) {
        sp<IMemory> mem = mMemoryDealer->allocate(kBufferSize);
        CHECK(mem != NULL);

        mBuffers.push(mem);
    }
    mSource->setBuffers(mBuffers);
}

void NuPlayer::NuPlayerStreamListener::start() {
    for (size_t i = 0; i < kNumBuffers; ++i) {
        mSource->onBufferAvailable(i);
    }
}

void NuPlayer::NuPlayerStreamListener::queueBuffer(size_t index, size_t size) {
    QueueEntry entry;
    entry.mIsCommand = false;
    entry.mIndex = index;
    entry.mSize = size;
    entry.mOffset = 0;

    Mutex::Autolock autoLock(mLock);
    mQueue.push_back(entry);

    if (mSendDataNotification) {
        mSendDataNotification = false;

        if (mTargetID != 0) {
            (new AMessage(kWhatMoreDataQueued, mTargetID))->post();
        }
    }
}

void NuPlayer::NuPlayerStreamListener::issueCommand(
        Command cmd, bool synchronous, const sp<AMessage> &extra) {
    CHECK(!synchronous);

    QueueEntry entry;
    entry.mIsCommand = true;
    entry.mCommand = cmd;
    entry.mExtra = extra;

    Mutex::Autolock autoLock(mLock);
    mQueue.push_back(entry);

    if (mSendDataNotification) {
        mSendDataNotification = false;

        if (mTargetID != 0) {
            (new AMessage(kWhatMoreDataQueued, mTargetID))->post();
        }
    }
}

ssize_t NuPlayer::NuPlayerStreamListener::read(
        void *data, size_t size, sp<AMessage> *extra) {
    CHECK_GT(size, 0u);

    extra->clear();

    Mutex::Autolock autoLock(mLock);

    if (mEOS) {
        return 0;
    }

    if (mQueue.empty()) {
        mSendDataNotification = true;

        return -EWOULDBLOCK;
    }

    QueueEntry *entry = &*mQueue.begin();

    if (entry->mIsCommand) {
        switch (entry->mCommand) {
            case EOS:
            {
                mQueue.erase(mQueue.begin());
                entry = NULL;

                mEOS = true;
                return 0;
            }

            case DISCONTINUITY:
            {
                *extra = entry->mExtra;

                mQueue.erase(mQueue.begin());
                entry = NULL;

                return INFO_DISCONTINUITY;
            }

            default:
                TRESPASS();
                break;
        }
    }

    size_t copy = entry->mSize;
    if (copy > size) {
        copy = size;
    }

    memcpy(data,
           (const uint8_t *)mBuffers.editItemAt(entry->mIndex)->pointer()
            + entry->mOffset,
           copy);

    entry->mOffset += copy;
    entry->mSize -= copy;

    if (entry->mSize == 0) {
        mSource->onBufferAvailable(entry->mIndex);
        mQueue.erase(mQueue.begin());
        entry = NULL;
    }

    return copy;
}

}  // namespace android
