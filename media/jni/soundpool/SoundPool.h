/*
 * Copyright (C) 2007 The Android Open Source Project
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

#include "SoundManager.h"
#include "StreamManager.h"

#include <string>

namespace android {

/**
 * Native class for Java SoundPool, manages a pool of sounds.
 *
 * See the Android SoundPool Java documentation for description of valid values.
 * https://developer.android.com/reference/android/media/SoundPool
 */
class SoundPool {
public:
    SoundPool(int32_t maxStreams, const audio_attributes_t& attributes,
            const std::string& opPackageName = {});
    ~SoundPool();

    // SoundPool Java API support
    int32_t load(int fd, int64_t offset, int64_t length, int32_t priority);
    bool unload(int32_t soundID);
    int32_t play(int32_t soundID, float leftVolume, float rightVolume, int32_t priority,
            int32_t loop, float rate);
    void pause(int32_t streamID);
    void autoPause();
    void resume(int32_t streamID);
    void autoResume();
    void stop(int32_t streamID);
    void setVolume(int32_t streamID, float leftVolume, float rightVolume);
    void setPriority(int32_t streamID, int32_t priority);
    void setLoop(int32_t streamID, int32_t loop);
    void setRate(int32_t streamID, float rate);
    void setCallback(SoundPoolCallback* callback, void* user);
    void* getUserData() const;

    // not exposed in the public Java API, used for internal playerSetVolume() muting.
    void mute(bool muting);

private:

    // Constructor initialized variables
    // Can access without lock as they are internally locked,
    // though care needs to be taken that the final result composed of
    // individually consistent actions are consistent.
    soundpool::SoundManager  mSoundManager;
    soundpool::StreamManager mStreamManager;

    // mApiLock serializes SoundPool application calls (configurable by kUseApiLock).
    // It only locks at the SoundPool layer and not below.  At this level,
    // mApiLock is only required for autoPause() and autoResume() to prevent zippering
    // of the individual pauses and resumes, and mute() for self-interaction with itself.
    // It is optional for all other apis.
    mutable std::mutex        mApiLock;
};

} // end namespace android
