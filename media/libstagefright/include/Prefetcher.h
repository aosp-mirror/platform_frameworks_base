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

#ifndef PREFETCHER_H_

#define PREFETCHER_H_

#include <utils/RefBase.h>
#include <utils/Vector.h>
#include <utils/threads.h>

namespace android {

struct MediaSource;
struct PrefetchedSource;

struct Prefetcher : public RefBase {
    Prefetcher();

    // Given an existing MediaSource returns a new MediaSource
    // that will benefit from prefetching/caching the original one.
    sp<MediaSource> addSource(const sp<MediaSource> &source);

    int64_t getCachedDurationUs();

protected:
    virtual ~Prefetcher();

private:
    Mutex mLock;
    Condition mCondition;

    Vector<wp<PrefetchedSource> > mSources;
    android_thread_id_t mThread;
    bool mDone;
    bool mThreadExited;

    void startThread();
    void stopThread();

    static int ThreadWrapper(void *me);
    void threadFunc();

    Prefetcher(const Prefetcher &);
    Prefetcher &operator=(const Prefetcher &);
};

}  // namespace android

#endif  // PREFETCHER_H_
