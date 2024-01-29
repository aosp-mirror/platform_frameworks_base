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
#define LOG_TAG "SoundPool::SoundManager"
#include <utils/Log.h>

#include "SoundManager.h"

#include <thread>

#include "SoundDecoder.h"

namespace android::soundpool {

static const size_t kDecoderThreads = std::thread::hardware_concurrency() >= 4 ? 2 : 1;

SoundManager::SoundManager()
    : mDecoder{std::make_unique<SoundDecoder>(this, kDecoderThreads, ANDROID_PRIORITY_NORMAL)}
{
    ALOGV("%s()", __func__);
}

SoundManager::~SoundManager()
{
    ALOGV("%s()", __func__);
    mDecoder->quit();

    std::lock_guard lock(mSoundManagerLock);
    mSounds.clear();
}

int32_t SoundManager::load(int fd, int64_t offset, int64_t length, int32_t priority)
{
    ALOGV("%s(fd=%d, offset=%lld, length=%lld, priority=%d)",
            __func__, fd, (long long)offset, (long long)length, priority);
    int32_t soundID;
    {
        std::lock_guard lock(mSoundManagerLock);
        // mNextSoundID is always positive and does not "integer overflow"
        do {
            mNextSoundID = mNextSoundID == INT32_MAX ? 1 : mNextSoundID + 1;
        } while (findSound_l(mNextSoundID) != nullptr);
        soundID = mNextSoundID;
        auto sound = std::make_shared<Sound>(soundID, fd, offset, length);
        mSounds.emplace(soundID, sound);
    }
    // mDecoder->loadSound() must be called outside of mSoundManagerLock.
    // mDecoder->loadSound() may block on mDecoder message queue space;
    // the message queue emptying may block on SoundManager::findSound().
    //
    // It is theoretically possible that sound loads might decode out-of-order.
    mDecoder->loadSound(soundID);
    return soundID;
}

bool SoundManager::unload(int32_t soundID)
{
    ALOGV("%s(soundID=%d)", __func__, soundID);
    std::lock_guard lock(mSoundManagerLock);
    return mSounds.erase(soundID) > 0; // erase() returns number of sounds removed.
}

std::shared_ptr<Sound> SoundManager::findSound(int32_t soundID) const
{
    std::lock_guard lock(mSoundManagerLock);
    return findSound_l(soundID);
}

std::shared_ptr<Sound> SoundManager::findSound_l(int32_t soundID) const
{
    auto it = mSounds.find(soundID);
    return it != mSounds.end() ? it->second : nullptr;
}

void SoundManager::setCallback(SoundPool *soundPool, SoundPoolCallback* callback, void* user)
{
    mCallbackHandler.setCallback(soundPool, callback, user);
}

void SoundManager::notify(SoundPoolEvent event)
{
    mCallbackHandler.notify(event);
}

void* SoundManager::getUserData() const
{
    return mCallbackHandler.getUserData();
}

} // namespace android::soundpool
