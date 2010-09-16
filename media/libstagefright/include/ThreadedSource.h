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

#ifndef THREADED_SOURCE_H_

#define THREADED_SOURCE_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/AHandlerReflector.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/MediaSource.h>
#include <utils/threads.h>

namespace android {

struct ThreadedSource : public MediaSource {
    ThreadedSource(const sp<MediaSource> &source);

    virtual status_t start(MetaData *params);
    virtual status_t stop();

    virtual sp<MetaData> getFormat();

    virtual status_t read(
            MediaBuffer **buffer, const ReadOptions *options);

    virtual void onMessageReceived(const sp<AMessage> &msg);

protected:
    virtual ~ThreadedSource();

private:
    enum {
        kWhatDecodeMore = 'deco',
        kWhatSeek       = 'seek',
    };

    sp<MediaSource> mSource;
    sp<AHandlerReflector<ThreadedSource> > mReflector;
    sp<ALooper> mLooper;

    Mutex mLock;
    Condition mCondition;
    List<MediaBuffer *> mQueue;
    status_t mFinalResult;
    bool mDecodePending;
    bool mStarted;

    int64_t mSeekTimeUs;
    ReadOptions::SeekMode mSeekMode;

    void postDecodeMore_l();
    void clearQueue_l();

    DISALLOW_EVIL_CONSTRUCTORS(ThreadedSource);
};

}  // namespace android

#endif  // THREADED_SOURCE_H_
