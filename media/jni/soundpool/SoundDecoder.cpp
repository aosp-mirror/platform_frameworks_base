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

//#define LOG_NDEBUG 0
#define LOG_TAG "SoundPool::SoundDecoder"
#include "utils/Log.h"

#include "SoundDecoder.h"

namespace android::soundpool {

// Maximum Samples that can be background decoded before we block the caller.
static constexpr size_t kMaxQueueSize = 128;

// The amount of time we wait for a new Sound decode request
// before the SoundDecoder thread closes.
static constexpr int32_t kWaitTimeBeforeCloseMs = 1000;

SoundDecoder::SoundDecoder(SoundManager* soundManager, size_t threads)
    : mSoundManager(soundManager)
{
    ALOGV("%s(%p, %zu)", __func__, soundManager, threads);
    // ThreadPool is created, but we don't launch any threads.
    mThreadPool = std::make_unique<ThreadPool>(
            std::min(threads, (size_t)std::thread::hardware_concurrency()),
            "SoundDecoder_");
}

SoundDecoder::~SoundDecoder()
{
    ALOGV("%s()", __func__);
    quit();
}

void SoundDecoder::quit()
{
    ALOGV("%s()", __func__);
    {
        std::lock_guard lock(mLock);
        mQuit = true;
        mQueueSpaceAvailable.notify_all(); // notify all load waiters
        mQueueDataAvailable.notify_all();  // notify all worker threads
    }
    mThreadPool->quit();
}

void SoundDecoder::run(int32_t id)
{
    ALOGV("%s(%d): entering", __func__, id);
    std::unique_lock lock(mLock);
    while (!mQuit) {
        if (mSoundIDs.size() == 0) {
            ALOGV("%s(%d): waiting", __func__, id);
            mQueueDataAvailable.wait_for(
                    lock, std::chrono::duration<int32_t, std::milli>(kWaitTimeBeforeCloseMs));
            if (mSoundIDs.size() == 0) {
                break; // no new sound, exit this thread.
            }
            continue;
        }
        const int32_t soundID = mSoundIDs.front();
        mSoundIDs.pop_front();
        mQueueSpaceAvailable.notify_one();
        ALOGV("%s(%d): processing soundID: %d  size: %zu", __func__, id, soundID, mSoundIDs.size());
        lock.unlock();
        std::shared_ptr<Sound> sound = mSoundManager->findSound(soundID);
        status_t status = NO_INIT;
        if (sound.get() != nullptr) {
            status = sound->doLoad();
        }
        ALOGV("%s(%d): notifying loaded soundID:%d  status:%d", __func__, id, soundID, status);
        mSoundManager->notify(SoundPoolEvent(SoundPoolEvent::SOUND_LOADED, soundID, status));
        lock.lock();
    }
    ALOGV("%s(%d): exiting", __func__, id);
}

void SoundDecoder::loadSound(int32_t soundID)
{
    ALOGV("%s(%d)", __func__, soundID);
    size_t pendingSounds;
    {
        std::unique_lock lock(mLock);
        while (mSoundIDs.size() == kMaxQueueSize) {
            if (mQuit) return;
            ALOGV("%s: waiting soundID: %d size: %zu", __func__, soundID, mSoundIDs.size());
            mQueueSpaceAvailable.wait(lock);
        }
        if (mQuit) return;
        mSoundIDs.push_back(soundID);
        mQueueDataAvailable.notify_one();
        ALOGV("%s: adding soundID: %d  size: %zu", __func__, soundID, mSoundIDs.size());
        pendingSounds = mSoundIDs.size();
    }
    // Launch threads as needed.  The "as needed" is weakly consistent as we release mLock.
    if (pendingSounds > mThreadPool->getActiveThreadCount()) {
        const int32_t id = mThreadPool->launch([this](int32_t id) { run(id); });
        (void)id; // avoid clang warning -Wunused-variable -Wused-but-marked-unused
        ALOGV_IF(id != 0, "%s: launched thread %d", __func__, id);
    }
}

} // end namespace android::soundpool
