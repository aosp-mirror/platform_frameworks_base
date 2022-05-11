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

#include <any>
#include <android-base/thread_annotations.h>
#include <audio_utils/clock.h>
#include <media/AudioTrack.h>

namespace android::soundpool {

// This is the amount of time to wait after stop is called when stealing an
// AudioTrack to allow the sound to ramp down.  If this is 0, glitches
// may occur when stealing an AudioTrack.
inline constexpr int64_t kStopWaitTimeNs = 20 * NANOS_PER_MILLISECOND;

inline constexpr size_t kCacheLineSize = 64; /* std::hardware_constructive_interference_size */

class StreamManager; // forward decl

/**
 * A Stream is associated with a StreamID exposed to the app to play a Sound.
 *
 * The Stream uses monitor locking strategy on mLock.
 * https://en.wikipedia.org/wiki/Monitor_(synchronization)
 *
 * where public methods are guarded by a lock (as needed)
 *
 * For Java equivalent APIs, see
 * https://developer.android.com/reference/android/media/SoundPool
 *
 * Streams are paired by the StreamManager, so one stream in the pair may be "stopping"
 * while the other stream of the pair has been prepared to run
 * (and the streamID returned to the app) pending its pair to be stopped.
 * The pair of a Stream may be obtained by calling getPairStream(),
 * where this->getPairStream()->getPairStream() == this; (pair is a commutative relationship).
 *
 * playPairStream() and getPairPriority() access the paired stream.
 * See also StreamManager.h for details of physical layout implications of paired streams.
 */
class alignas(kCacheLineSize) Stream {
public:
    enum state { IDLE, PAUSED, PLAYING };
    // The PAUSED, PLAYING state directly corresponds to the AudioTrack state of an active Stream.
    //
    // The IDLE state indicates an inactive Stream.   An IDLE Stream may have a non-nullptr
    // AudioTrack, which may be recycled for use if the SoundID matches the next Stream playback.
    //
    // PAUSED -> PLAYING through resume()  (see also autoResume())
    // PLAYING -> PAUSED through pause()   (see also autoPause())
    //
    // IDLE is the initial state of a Stream and also when a stream becomes inactive.
    // {PAUSED, PLAYING} -> IDLE through stop() (or if the Sound finishes playing)
    // IDLE -> PLAYING through play().  (there is no way to start a Stream in paused mode).

    ~Stream();
    void setStreamManager(StreamManager* streamManager) { // non-nullptr
        mStreamManager = streamManager; // set in StreamManager constructor, not changed
    }

    // The following methods are monitor locked by mLock.
    //
    // For methods taking a streamID:
    // if the streamID matches the Stream's mStreamID, then method proceeds
    // else the command is ignored with no effect.

    // returns true if the stream needs to be explicitly stopped.
    bool requestStop(int32_t streamID);
    void stop();                    // explicit stop(), typically called from the worker thread.
    void clearAudioTrack();
    void pause(int32_t streamID);
    void autoPause();               // see the Java SoundPool.autoPause documentation for details.
    void resume(int32_t streamID);
    void autoResume();
    void mute(bool muting);
    void dump() const NO_THREAD_SAFETY_ANALYSIS; // disable for ALOGV (see func for details).

    // returns the pair stream if successful, nullptr otherwise.
    // garbage is used to release tracks and data outside of any lock.
    Stream* playPairStream(std::vector<std::any>& garbage);

    // These parameters are explicitly checked in the SoundPool class
    // so never deviate from the Java API specified values.
    void setVolume(int32_t streamID, float leftVolume, float rightVolume);
    void setRate(int32_t streamID, float rate);
    void setPriority(int32_t streamID, int priority);
    void setLoop(int32_t streamID, int loop);
    void setPlay(int32_t streamID, const std::shared_ptr<Sound> &sound, int32_t soundID,
           float leftVolume, float rightVolume, int32_t priority, int32_t loop, float rate);
    void setStopTimeNs(int64_t stopTimeNs); // systemTime() clock monotonic.

    // The following getters are not locked and have weak consistency.
    // These are considered advisory only - being stale is of nuisance.
    int32_t getPriority() const NO_THREAD_SAFETY_ANALYSIS { return mPriority; }
    int32_t getPairPriority() const NO_THREAD_SAFETY_ANALYSIS {
        return getPairStream()->getPriority();
    }
    int64_t getStopTimeNs() const NO_THREAD_SAFETY_ANALYSIS { return mStopTimeNs; }

    // Can change with setPlay()
    int32_t getStreamID() const NO_THREAD_SAFETY_ANALYSIS { return mStreamID; }

    // Can change with play_l()
    int32_t getSoundID() const NO_THREAD_SAFETY_ANALYSIS { return mSoundID; }

    bool hasSound() const NO_THREAD_SAFETY_ANALYSIS { return mSound.get() != nullptr; }

    // This never changes.  See top of header.
    Stream* getPairStream() const;

    // Stream ID of ourselves, or the pair depending on who holds the AudioTrack
    int getCorrespondingStreamID();

protected:
    // AudioTrack callback interface implementation
    class StreamCallback : public AudioTrack::IAudioTrackCallback {
      public:
        StreamCallback(Stream * stream, bool toggle) : mStream(stream), mToggle(toggle) {}
        size_t onMoreData(const AudioTrack::Buffer& buffer) override;
        void onUnderrun() override;
        void onLoopEnd(int32_t loopsRemaining) override;
        void onMarker(uint32_t markerPosition) override;
        void onNewPos(uint32_t newPos) override;
        void onBufferEnd() override;
        void onNewIAudioTrack() override;
        void onStreamEnd() override;
        size_t onCanWriteMoreData(const AudioTrack::Buffer& buffer) override;

        // Holding a raw ptr is technically unsafe, but, Stream objects persist
        // through the lifetime of the StreamManager through the use of a
        // unique_ptr<Stream[]>. Ensuring lifetime will cause us to give up
        // locality as well as pay RefBase/sp performance cost, which we are
        // unwilling to do. Non-owning refs to unique_ptrs are idiomatically raw
        // ptrs, as below.
        Stream * const mStream;
        const bool mToggle;
    };

    sp<StreamCallback> mCallback;
private:
    // garbage is used to release tracks and data outside of any lock.
    void play_l(const std::shared_ptr<Sound>& sound, int streamID,
            float leftVolume, float rightVolume, int priority, int loop, float rate,
            std::vector<std::any>& garbage) REQUIRES(mLock);
    void stop_l() REQUIRES(mLock);
    void setVolume_l(float leftVolume, float rightVolume) REQUIRES(mLock);

    // For use with AudioTrack callback.
    void onBufferEnd(int toggle, int tries) NO_THREAD_SAFETY_ANALYSIS;

    // StreamManager should be set on construction and not changed.
    // release mLock before calling into StreamManager
    StreamManager*     mStreamManager = nullptr;

    mutable std::mutex  mLock;
    std::atomic_int32_t mStreamID GUARDED_BY(mLock) = 0; // Valid streamIDs are always positive.
    int                 mState GUARDED_BY(mLock) = IDLE;
    std::shared_ptr<Sound> mSound GUARDED_BY(mLock);    // Non-null if playing.
    int32_t             mSoundID GUARDED_BY(mLock) = 0; // SoundID associated with AudioTrack.
    float               mLeftVolume GUARDED_BY(mLock) = 0.f;
    float               mRightVolume GUARDED_BY(mLock) = 0.f;
    int32_t             mPriority GUARDED_BY(mLock) = INT32_MIN;
    int32_t             mLoop GUARDED_BY(mLock) = 0;
    float               mRate GUARDED_BY(mLock) = 0.f;
    bool                mAutoPaused GUARDED_BY(mLock) = false;
    bool                mMuted GUARDED_BY(mLock) = false;

    sp<AudioTrack>      mAudioTrack GUARDED_BY(mLock);
    int                 mToggle GUARDED_BY(mLock) = 0;
    int64_t             mStopTimeNs GUARDED_BY(mLock) = 0;  // if nonzero, time to wait for stop.
};

} // namespace android::soundpool
