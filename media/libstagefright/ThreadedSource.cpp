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

#include "include/ThreadedSource.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MetaData.h>

namespace android {

static const size_t kMaxQueueSize = 2;

ThreadedSource::ThreadedSource(const sp<MediaSource> &source)
    : mSource(source),
      mReflector(new AHandlerReflector<ThreadedSource>(this)),
      mLooper(new ALooper),
      mStarted(false) {
    mLooper->registerHandler(mReflector);
}

ThreadedSource::~ThreadedSource() {
    if (mStarted) {
        stop();
    }
}

status_t ThreadedSource::start(MetaData *params) {
    CHECK(!mStarted);

    status_t err = mSource->start(params);

    if (err != OK) {
        return err;
    }

    mFinalResult = OK;
    mSeekTimeUs = -1;
    mDecodePending = false;

    Mutex::Autolock autoLock(mLock);
    postDecodeMore_l();

    CHECK_EQ(mLooper->start(), (status_t)OK);

    mStarted = true;

    return OK;
}

status_t ThreadedSource::stop() {
    CHECK(mStarted);

    CHECK_EQ(mLooper->stop(), (status_t)OK);

    Mutex::Autolock autoLock(mLock);
    clearQueue_l();

    status_t err = mSource->stop();

    mStarted = false;

    return err;
}

sp<MetaData> ThreadedSource::getFormat() {
    return mSource->getFormat();
}

status_t ThreadedSource::read(
        MediaBuffer **buffer, const ReadOptions *options) {
    *buffer = NULL;

    Mutex::Autolock autoLock(mLock);

    int64_t seekTimeUs;
    ReadOptions::SeekMode seekMode;
    if (options && options->getSeekTo(&seekTimeUs, &seekMode)) {
        int32_t seekComplete = 0;

        sp<AMessage> msg = new AMessage(kWhatSeek, mReflector->id());
        msg->setInt64("timeUs", seekTimeUs);
        msg->setInt32("mode", seekMode);
        msg->setPointer("complete", &seekComplete);
        msg->post();

        while (!seekComplete) {
            mCondition.wait(mLock);
        }
    }

    while (mQueue.empty() && mFinalResult == OK) {
        mCondition.wait(mLock);
    }

    if (!mQueue.empty()) {
        *buffer = *mQueue.begin();
        mQueue.erase(mQueue.begin());

        if (mFinalResult == OK) {
            postDecodeMore_l();
        }

        return OK;
    }

    return mFinalResult;
}

void ThreadedSource::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatSeek:
        {
            CHECK(msg->findInt64("timeUs", &mSeekTimeUs));
            CHECK_GE(mSeekTimeUs, 0ll);

            int32_t x;
            CHECK(msg->findInt32("mode", &x));
            mSeekMode = (ReadOptions::SeekMode)x;

            int32_t *seekComplete;
            CHECK(msg->findPointer("complete", (void **)&seekComplete));

            Mutex::Autolock autoLock(mLock);
            clearQueue_l();
            mFinalResult = OK;

            *seekComplete = 1;
            mCondition.signal();

            postDecodeMore_l();
            break;
        }

        case kWhatDecodeMore:
        {
            {
                Mutex::Autolock autoLock(mLock);
                mDecodePending = false;

                if (mQueue.size() == kMaxQueueSize) {
                    break;
                }
            }

            MediaBuffer *buffer;
            ReadOptions options;
            if (mSeekTimeUs >= 0) {
                options.setSeekTo(mSeekTimeUs, mSeekMode);
                mSeekTimeUs = -1ll;
            }
            status_t err = mSource->read(&buffer, &options);

            Mutex::Autolock autoLock(mLock);

            if (err != OK) {
                mFinalResult = err;
            } else {
                mQueue.push_back(buffer);

                if (mQueue.size() < kMaxQueueSize) {
                    postDecodeMore_l();
                }
            }

            mCondition.signal();
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

void ThreadedSource::postDecodeMore_l() {
    if (mDecodePending) {
        return;
    }

    mDecodePending = true;
    (new AMessage(kWhatDecodeMore, mReflector->id()))->post();
}

void ThreadedSource::clearQueue_l() {
    while (!mQueue.empty()) {
        MediaBuffer *buffer = *mQueue.begin();
        mQueue.erase(mQueue.begin());

        buffer->release();
        buffer = NULL;
    }
}

}  // namespace android
