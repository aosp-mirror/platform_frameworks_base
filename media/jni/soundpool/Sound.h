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

#include <android-base/unique_fd.h>
#include <binder/MemoryBase.h>
#include <binder/MemoryHeapBase.h>
#include <system/audio.h>

namespace android::soundpool {

class SoundDecoder;

/**
 * Sound is a resource used by SoundPool, referenced by soundID.
 *
 * After loading, it is effectively const so no locking required.
 * However, in order to guarantee that all the values have been
 * written properly and read properly, we use the mState as an atomic synchronization
 * point.  So if getState() shows READY, then all the other getters may
 * be safely read.
 *
 * Technical details:
 * We access the mState atomic value through memory_order_seq_cst
 *
 * https://en.cppreference.com/w/cpp/atomic/memory_order
 *
 * which provides memory barriers.  So if the last value written by the SoundDecoder
 * is mState, then the compiler ensures no other prior writes by SoundDecoder will be
 * reordered afterwards, and memory barrier is placed (as necessary) to ensure the
 * cache is visible to other processors.
 *
 * Likewise, if the first value read by SoundPool is mState,
 * the compiler ensures no reads for that thread will be reordered before mState is read,
 * and a memory barrier is placed (as necessary) to ensure that the cache is properly
 * updated with other processor's writes before reading.
 *
 * See https://developer.android.com/training/articles/smp for discussions about
 * the variant load-acquire, store-release semantics.
 */
class Sound {
    friend SoundDecoder;  // calls doLoad().

public:
    enum sound_state : int32_t { LOADING, READY, DECODE_ERROR };
    // A sound starts in the LOADING state and transitions only once
    // to either READY or DECODE_ERROR when doLoad() is called.

    Sound(int soundID, int fd, int64_t offset, int64_t length);
    ~Sound();

    int32_t getSoundID() const { return mSoundID; }
    int32_t getChannelCount() const { return mChannelCount; }
    uint32_t getSampleRate() const { return mSampleRate; }
    audio_format_t getFormat() const { return mFormat; }
    audio_channel_mask_t getChannelMask() const { return mChannelMask; }
    size_t getSizeInBytes() const { return mSizeInBytes; }
    sound_state getState() const { return mState; }
    uint8_t* getData() const { return static_cast<uint8_t*>(mData->unsecurePointer()); }
    sp<IMemory> getIMemory() const { return mData; }

private:
    status_t doLoad();  // only SoundDecoder accesses this.

    size_t               mSizeInBytes = 0;
    const int32_t        mSoundID;
    uint32_t             mSampleRate = 0;
    std::atomic<sound_state> mState = LOADING; // used as synchronization point
    int32_t              mChannelCount = 0;
    audio_format_t       mFormat = AUDIO_FORMAT_INVALID;
    audio_channel_mask_t mChannelMask = AUDIO_CHANNEL_NONE;
    base::unique_fd      mFd;     // initialized in constructor, reset to -1 after loading
    const int64_t        mOffset; // int64_t to match java long, see off64_t
    const int64_t        mLength; // int64_t to match java long, see off64_t
    sp<IMemory>          mData;
    sp<MemoryHeapBase>   mHeap;
};

} // namespace android::soundpool
