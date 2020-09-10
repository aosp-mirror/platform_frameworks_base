/*
 * Copyright (C) 2019 The Android Open Source Project
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

#pragma once

#include "SoundPool.h"

#include <deque>
#include <mutex>

namespace android::soundpool {

/**
 * SoundDecoder handles background decoding tasks.
 */
class SoundDecoder {
public:
    SoundDecoder(SoundManager* soundManager, size_t threads);
    ~SoundDecoder();
    void loadSound(int32_t soundID) NO_THREAD_SAFETY_ANALYSIS; // uses unique_lock
    void quit();

private:
    // The decode thread function.
    void run(int32_t id) NO_THREAD_SAFETY_ANALYSIS; // uses unique_lock

    SoundManager* const     mSoundManager;      // set in constructor, has own lock
    std::unique_ptr<ThreadPool> mThreadPool;    // set in constructor, has own lock

    std::mutex              mLock;
    std::condition_variable mQueueSpaceAvailable GUARDED_BY(mLock);
    std::condition_variable mQueueDataAvailable GUARDED_BY(mLock);

    std::deque<int32_t>     mSoundIDs GUARDED_BY(mLock);
    bool                    mQuit GUARDED_BY(mLock) = false;
};

} // end namespace android::soundpool

