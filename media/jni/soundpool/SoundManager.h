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

#include "Sound.h"

#include <mutex>
#include <unordered_map>

#include <android-base/thread_annotations.h>

namespace android {

class SoundPool;

// for queued events
class SoundPoolEvent {
public:
    explicit SoundPoolEvent(int msg, int arg1 = 0, int arg2 = 0) :
        mMsg(msg), mArg1(arg1), mArg2(arg2) {}
    const int mMsg;   // MessageType
    const int mArg1;  // soundID
    const int mArg2;  // status
    enum MessageType { INVALID, SOUND_LOADED };
};

// callback function prototype
typedef void SoundPoolCallback(SoundPoolEvent event, SoundPool* soundPool, void* user);

} // namespace android

namespace android::soundpool {

// This class manages Sounds for the SoundPool.
class SoundManager {
public:
    SoundManager();
    ~SoundManager();

    // Matches corresponding SoundPool API functions
    int32_t load(int fd, int64_t offset, int64_t length, int32_t priority);
    bool unload(int32_t soundID);
    void setCallback(SoundPool* soundPool, SoundPoolCallback* callback, void* user);
    void* getUserData() const;

    // SoundPool and SoundDecoder access
    std::shared_ptr<Sound> findSound(int32_t soundID) const;

    // from the SoundDecoder
    void notify(SoundPoolEvent event);

private:

    // CallbackHandler is used to manage notifications back to the app when a sound
    // has been loaded.  It uses a recursive lock to allow setting the callback
    // during the callback.
    class CallbackHandler {
    public:
        void setCallback(SoundPool *soundPool, SoundPoolCallback* callback, void* userData)
        {
            std::lock_guard<std::recursive_mutex> lock(mCallbackLock);
            mSoundPool = soundPool;
            mCallback = callback;
            mUserData = userData;
        }
        void notify(SoundPoolEvent event) const
        {
            std::lock_guard<std::recursive_mutex> lock(mCallbackLock);
            if (mCallback != nullptr) {
                mCallback(event, mSoundPool, mUserData);
                // Note: mCallback may call setCallback().
                // so mCallback, mUserData may have changed.
            }
        }
        void* getUserData() const
        {
            std::lock_guard<std::recursive_mutex> lock(mCallbackLock);
            return mUserData;
        }
    private:
        mutable std::recursive_mutex  mCallbackLock; // allow mCallback to setCallback().
                                          // No thread-safety checks in R for recursive_mutex.
        SoundPool*          mSoundPool = nullptr; // GUARDED_BY(mCallbackLock)
        SoundPoolCallback*  mCallback = nullptr;  // GUARDED_BY(mCallbackLock)
        void*               mUserData = nullptr;  // GUARDED_BY(mCallbackLock)
    };

    std::shared_ptr<Sound> findSound_l(int32_t soundID) const REQUIRES(mSoundManagerLock);

    // The following variables are initialized in constructor and can be accessed anytime.
    CallbackHandler mCallbackHandler;              // has its own lock
    const std::unique_ptr<SoundDecoder> mDecoder;  // has its own lock

    mutable std::mutex mSoundManagerLock;
    std::unordered_map<int, std::shared_ptr<Sound>> mSounds GUARDED_BY(mSoundManagerLock);
    int32_t mNextSoundID GUARDED_BY(mSoundManagerLock) = 0;
};

} // namespace android::soundpool
