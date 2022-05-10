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

//#define LOG_NDEBUG 0
#define LOG_TAG "SoundPool"
#include <utils/Log.h>

#include <algorithm>
#include <thread>

#include "SoundPool.h"

namespace android
{

// kManagerThreads = 1 historically.
// Not really necessary to have more than one, but it does speed things up by about
// 25% having 2 threads instead of 1 when playing many sounds.  Having many threads
// could starve other AudioFlinger clients with SoundPool activity. It may also cause
// issues with app loading, e.g. Camera.
static const size_t kStreamManagerThreads = std::thread::hardware_concurrency() >= 4 ? 2 : 1;

// kUseApiLock = true prior to R.
// Set to true to prevent multiple users access internal to the SoundPool API.
// Set to false to make the SoundPool methods weakly consistent.  When set to false,
// only AutoPause and AutoResume are locked, which are the only two methods that
// require API level locking for consistency.
static constexpr bool kUseApiLock = false;

namespace {
// Check input arguments to SoundPool - return "true" to reject request.

bool checkVolume(float *leftVolume, float *rightVolume)
{
    if (*leftVolume != std::clamp(*leftVolume, 0.f, 1.f) ||
            *rightVolume != std::clamp(*rightVolume, 0.f, 1.f)) {
        ALOGI("volume l=%f r=%f out of (0.f, 1.f) bounds, using 1.f", *leftVolume, *rightVolume);
        // for backward compatibility use 1.f.
        *leftVolume = *rightVolume = 1.f;
    }
    return false;
}

bool checkRate(float *rate)
{
    if (*rate != std::clamp(*rate, 0.125f, 8.f)) {
        ALOGI("rate %f out of (0.125f, 8.f) bounds, clamping", *rate);
        // for backward compatibility just clamp
        *rate = std::clamp(*rate, 0.125f, 8.f);
    }
    return false;
}

bool checkPriority(int32_t *priority)
{
    if (*priority < 0) {
        ALOGI("negative priority %d, should be >= 0.", *priority);
        // for backward compatibility, ignore.
    }
    return false;
}

bool checkLoop(int32_t *loop)
{
    if (*loop < -1) {
        ALOGI("loop %d, should be >= -1", *loop);
        *loop = -1;
    }
    return false;
}

} // namespace

SoundPool::SoundPool(
        int32_t maxStreams, const audio_attributes_t& attributes,
        const std::string& opPackageName)
    : mStreamManager(maxStreams, kStreamManagerThreads, attributes, opPackageName)
{
    ALOGV("%s(maxStreams=%d, attr={ content_type=%d, usage=%d, flags=0x%x, tags=%s })",
            __func__, maxStreams,
            attributes.content_type, attributes.usage, attributes.flags, attributes.tags);
}

SoundPool::~SoundPool()
{
    ALOGV("%s()", __func__);
}

int32_t SoundPool::load(int fd, int64_t offset, int64_t length, int32_t priority)
{
    ALOGV("%s(fd=%d, offset=%lld, length=%lld, priority=%d)",
            __func__, fd, (long long)offset, (long long)length, priority);
    auto apiLock = kUseApiLock ? std::make_unique<std::lock_guard<std::mutex>>(mApiLock) : nullptr;
    return mSoundManager.load(fd, offset, length, priority);
}

bool SoundPool::unload(int32_t soundID)
{
    ALOGV("%s(%d)", __func__, soundID);
    auto apiLock = kUseApiLock ? std::make_unique<std::lock_guard<std::mutex>>(mApiLock) : nullptr;
    return mSoundManager.unload(soundID);
}

int32_t SoundPool::play(int32_t soundID, float leftVolume, float rightVolume,
        int32_t priority, int32_t loop, float rate)
{
    ALOGV("%s(soundID=%d, leftVolume=%f, rightVolume=%f, priority=%d, loop=%d, rate=%f)",
            __func__, soundID, leftVolume, rightVolume, priority, loop, rate);

    // New for R: check arguments to ensure track can be created.
    // If SoundPool defers the creation of the AudioTrack to the StreamManager thread,
    // the failure to create may not be visible to the caller, so this precheck is needed.
    if (checkVolume(&leftVolume, &rightVolume)
            || checkPriority(&priority)
            || checkLoop(&loop)
            || checkRate(&rate)) return 0;

    auto apiLock = kUseApiLock ? std::make_unique<std::lock_guard<std::mutex>>(mApiLock) : nullptr;
    const std::shared_ptr<soundpool::Sound> sound = mSoundManager.findSound(soundID);
    if (sound == nullptr || sound->getState() != soundpool::Sound::READY) {
        ALOGW("%s soundID %d not READY", __func__, soundID);
        return 0;
    }

    const int32_t streamID = mStreamManager.queueForPlay(
            sound, soundID, leftVolume, rightVolume, priority, loop, rate);
    ALOGV("%s returned %d", __func__, streamID);
    return streamID;
}

void SoundPool::autoPause()
{
    ALOGV("%s()", __func__);
    auto apiLock = std::make_unique<std::lock_guard<std::mutex>>(mApiLock);
    mStreamManager.forEach([](soundpool::Stream *stream) { stream->autoPause(); });
}

void SoundPool::autoResume()
{
    ALOGV("%s()", __func__);
    auto apiLock = std::make_unique<std::lock_guard<std::mutex>>(mApiLock);
    mStreamManager.forEach([](soundpool::Stream *stream) { stream->autoResume(); });
}

void SoundPool::mute(bool muting)
{
    ALOGV("%s(%d)", __func__, muting);
    auto apiLock = std::make_unique<std::lock_guard<std::mutex>>(mApiLock);
    mStreamManager.forEach([=](soundpool::Stream *stream) { stream->mute(muting); });
}

void SoundPool::pause(int32_t streamID)
{
    ALOGV("%s(%d)", __func__, streamID);
    auto apiLock = kUseApiLock ? std::make_unique<std::lock_guard<std::mutex>>(mApiLock) : nullptr;
    if (soundpool::Stream* stream = mStreamManager.findStream(streamID)) {
        stream->pause(streamID);
    }
}

void SoundPool::resume(int32_t streamID)
{
    ALOGV("%s(%d)", __func__, streamID);
    auto apiLock = kUseApiLock ? std::make_unique<std::lock_guard<std::mutex>>(mApiLock) : nullptr;
    if (soundpool::Stream* stream = mStreamManager.findStream(streamID)) {
        stream->resume(streamID);
    }
}

void SoundPool::stop(int32_t streamID)
{
    ALOGV("%s(%d)", __func__, streamID);
    auto apiLock = kUseApiLock ? std::make_unique<std::lock_guard<std::mutex>>(mApiLock) : nullptr;
    soundpool::Stream* stream = mStreamManager.findStream(streamID);
    if (stream != nullptr && stream->requestStop(streamID)) {
        mStreamManager.moveToRestartQueue(stream, streamID);
    }
}

void SoundPool::setVolume(int32_t streamID, float leftVolume, float rightVolume)
{
    ALOGV("%s(%d, %f %f)", __func__, streamID, leftVolume, rightVolume);
    if (checkVolume(&leftVolume, &rightVolume)) return;
    auto apiLock = kUseApiLock ? std::make_unique<std::lock_guard<std::mutex>>(mApiLock) : nullptr;
    if (soundpool::Stream* stream = mStreamManager.findStream(streamID)) {
        stream->setVolume(streamID, leftVolume, rightVolume);
    }
}

void SoundPool::setPriority(int32_t streamID, int32_t priority)
{
    ALOGV("%s(%d, %d)", __func__, streamID, priority);
    if (checkPriority(&priority)) return;
    auto apiLock = kUseApiLock ? std::make_unique<std::lock_guard<std::mutex>>(mApiLock) : nullptr;
    if (soundpool::Stream* stream = mStreamManager.findStream(streamID)) {
        stream->setPriority(streamID, priority);
    }
}

void SoundPool::setLoop(int32_t streamID, int32_t loop)
{
    ALOGV("%s(%d, %d)", __func__, streamID, loop);
    if (checkLoop(&loop)) return;
    auto apiLock = kUseApiLock ? std::make_unique<std::lock_guard<std::mutex>>(mApiLock) : nullptr;
    if (soundpool::Stream* stream = mStreamManager.findStream(streamID)) {
        stream->setLoop(streamID, loop);
    }
}

void SoundPool::setRate(int32_t streamID, float rate)
{
    ALOGV("%s(%d, %f)", __func__, streamID, rate);
    if (checkRate(&rate)) return;
    auto apiLock = kUseApiLock ? std::make_unique<std::lock_guard<std::mutex>>(mApiLock) : nullptr;
    if (soundpool::Stream* stream = mStreamManager.findStream(streamID)) {
        stream->setRate(streamID, rate);
    }
}

void SoundPool::setCallback(SoundPoolCallback* callback, void* user)
{
    ALOGV("%s(%p, %p)", __func__, callback, user);
    auto apiLock = kUseApiLock ? std::make_unique<std::lock_guard<std::mutex>>(mApiLock) : nullptr;
    mSoundManager.setCallback(this, callback, user);
}

void* SoundPool::getUserData() const
{
    ALOGV("%s()", __func__);
    auto apiLock = kUseApiLock ? std::make_unique<std::lock_guard<std::mutex>>(mApiLock) : nullptr;
    return mSoundManager.getUserData();
}

} // end namespace android
