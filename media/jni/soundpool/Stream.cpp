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
#define LOG_TAG "SoundPool::Stream"
#include <utils/Log.h>

#include "Stream.h"

#include "StreamManager.h"

namespace android::soundpool {

Stream::~Stream()
{
    ALOGV("%s(%p)", __func__, this);
}

void Stream::autoPause()
{
    std::lock_guard lock(mLock);
    if (mState == PLAYING) {
        ALOGV("%s: track streamID: %d", __func__, (int)mStreamID);
        mState = PAUSED;
        mAutoPaused = true;
        if (mAudioTrack != nullptr) {
            mAudioTrack->pause();
        }
    }
}

void Stream::autoResume()
{
    std::lock_guard lock(mLock);
    if (mAutoPaused) {
        if (mState == PAUSED) {
            ALOGV("%s: track streamID: %d", __func__, (int)mStreamID);
            mState = PLAYING;
            if (mAudioTrack != nullptr) {
                mAudioTrack->start();
            }
        }
        mAutoPaused = false; // New for R: always reset autopause (consistent with API spec).
    }
}

void Stream::mute(bool muting)
{
    std::lock_guard lock(mLock);
    mMuted = muting;
    if (mAudioTrack != nullptr) {
        if (mMuted) {
            mAudioTrack->setVolume(0.0f, 0.0f);
        } else {
            mAudioTrack->setVolume(mLeftVolume, mRightVolume);
        }
    }
}

void Stream::pause(int32_t streamID)
{
    std::lock_guard lock(mLock);
    if (streamID == mStreamID) {
        if (mState == PLAYING) {
            ALOGV("%s: track streamID: %d", __func__, streamID);
            mState = PAUSED;
            if (mAudioTrack != nullptr) {
                mAudioTrack->pause();
            }
        }
    }
}

void Stream::resume(int32_t streamID)
{
    std::lock_guard lock(mLock);
    if (streamID == mStreamID) {
         if (mState == PAUSED) {
            ALOGV("%s: track streamID: %d", __func__, streamID);
            mState = PLAYING;
            if (mAudioTrack != nullptr) {
                mAudioTrack->start();
            }
            mAutoPaused = false; // TODO: is this right? (ambiguous per spec), move outside?
        }
    }
}

void Stream::setRate(int32_t streamID, float rate)
{
    std::lock_guard lock(mLock);
    if (streamID == mStreamID) {
        mRate = rate;
        if (mAudioTrack != nullptr && mSound != nullptr) {
            const auto sampleRate = (uint32_t)lround(double(mSound->getSampleRate()) * rate);
            mAudioTrack->setSampleRate(sampleRate);
        }
    }
}

void Stream::setVolume_l(float leftVolume, float rightVolume)
{
    mLeftVolume = leftVolume;
    mRightVolume = rightVolume;
    if (mAudioTrack != nullptr && !mMuted) {
        mAudioTrack->setVolume(leftVolume, rightVolume);
    }
}

void Stream::setVolume(int32_t streamID, float leftVolume, float rightVolume)
{
    std::lock_guard lock(mLock);
    if (streamID == mStreamID) {
        setVolume_l(leftVolume, rightVolume);
    }
}

void Stream::setPriority(int32_t streamID, int32_t priority)
{
    std::lock_guard lock(mLock);
    if (streamID == mStreamID) {
        mPriority = priority;
    }
}

void Stream::setLoop(int32_t streamID, int32_t loop)
{
    std::lock_guard lock(mLock);
    if (streamID == mStreamID) {
        if (mAudioTrack != nullptr && mSound != nullptr) {
            const uint32_t loopEnd = mSound->getSizeInBytes() / mSound->getChannelCount() /
                (mSound->getFormat() == AUDIO_FORMAT_PCM_16_BIT
                        ? sizeof(int16_t) : sizeof(uint8_t));
            mAudioTrack->setLoop(0, loopEnd, loop);
        }
        mLoop = loop;
    }
}

void Stream::setPlay(
        int32_t streamID, const std::shared_ptr<Sound> &sound, int32_t soundID,
        float leftVolume, float rightVolume, int32_t priority, int32_t loop, float rate)
{
    std::lock_guard lock(mLock);
    // We must be idle, or we must be repurposing a pending Stream.
    LOG_ALWAYS_FATAL_IF(mState != IDLE && mAudioTrack != nullptr, "State %d must be IDLE", mState);
    mSound = sound;
    mSoundID = soundID;
    mLeftVolume = leftVolume;
    mRightVolume = rightVolume;
    mPriority = priority;
    mLoop = loop;
    mRate = rate;
    mState = PLAYING;
    mAutoPaused = false;   // New for R (consistent with Java API spec).
    mStreamID = streamID;  // prefer this to be the last, as it is an atomic sync point
}

void Stream::setStopTimeNs(int64_t stopTimeNs)
{
    std::lock_guard lock(mLock);
    mStopTimeNs = stopTimeNs;
}

bool Stream::requestStop(int32_t streamID)
{
    std::lock_guard lock(mLock);
    if (streamID == mStreamID) {
        ALOGV("%s: track streamID: %d", __func__, streamID);
        if (mAudioTrack != nullptr) {
            if (mState == PLAYING && !mMuted && (mLeftVolume != 0.f || mRightVolume != 0.f)) {
                setVolume_l(0.f, 0.f);
                mStopTimeNs = systemTime() + kStopWaitTimeNs;
            } else {
                mStopTimeNs = systemTime();
            }
            return true; // must be queued on the restart list.
        }
        stop_l();
    }
    return false;
}

void Stream::stop()
{
    std::lock_guard lock(mLock);
    stop_l();
}

void Stream::stop_l()
{
    if (mState != IDLE) {
        ALOGV("%s: track(%p) streamID: %d", __func__, mAudioTrack.get(), (int)mStreamID);
        if (mAudioTrack != nullptr) {
            mAudioTrack->stop();
        }
        mSound.reset();
        mState = IDLE;
    }
}

void Stream::clearAudioTrack()
{
    sp<AudioTrack> release;  // release outside of lock.
    std::lock_guard lock(mLock);
    // This will invoke the destructor which waits for the AudioTrack thread to join,
    // and is currently the only safe way to ensure there are no callbacks afterwards.
    release = mAudioTrack;  // or std::swap if we had move semantics.
    mAudioTrack.clear();
}

Stream* Stream::getPairStream() const
{
   return mStreamManager->getPairStream(this);
}

Stream* Stream::playPairStream() {
    Stream* pairStream = getPairStream();
    LOG_ALWAYS_FATAL_IF(pairStream == nullptr, "No pair stream!");
    sp<AudioTrack> releaseTracks[2];
    {
        ALOGV("%s: track streamID: %d", __func__, (int)getStreamID());
        // TODO: Do we really want to force a simultaneous synchronization between
        // the stream and its pair?

        // note locking order - the paired stream is obtained before the queued stream.
        // we can invert the locking order, but it is slightly more optimal to do it this way.
        std::lock_guard lockp(pairStream->mLock);
        if (pairStream->mSound == nullptr) {
            return nullptr; // no pair sound
        }
        {
            std::lock_guard lock(mLock);
            LOG_ALWAYS_FATAL_IF(mState != IDLE, "State: %d must be IDLE", mState);
            // TODO: do we want a specific set() here?
            pairStream->mAudioTrack = mAudioTrack;
            pairStream->mSoundID = mSoundID; // optimization to reuse AudioTrack.
            pairStream->mToggle = mToggle;
            pairStream->mAutoPaused = mAutoPaused; // save autopause state
            pairStream->mMuted = mMuted;
            mAudioTrack.clear();  // the pair owns the audiotrack.
            mSound.reset();
            mSoundID = 0;
        }
        // TODO: do we need a specific play_l() anymore?
        const int pairState = pairStream->mState;
        pairStream->play_l(pairStream->mSound, pairStream->mStreamID,
                pairStream->mLeftVolume, pairStream->mRightVolume, pairStream->mPriority,
                pairStream->mLoop, pairStream->mRate, releaseTracks);
        if (pairStream->mState == IDLE) {
            return nullptr; // AudioTrack error
        }
        if (pairState == PAUSED) {  // reestablish pause
            pairStream->mState = PAUSED;
            pairStream->mAudioTrack->pause();
        }
    }
    // release tracks outside of Stream lock
    return pairStream;
}

void Stream::play_l(const std::shared_ptr<Sound>& sound, int32_t nextStreamID,
        float leftVolume, float rightVolume, int32_t priority, int32_t loop, float rate,
        sp<AudioTrack> releaseTracks[2])
{
    // These tracks are released without the lock.
    sp<AudioTrack> &oldTrack = releaseTracks[0];
    sp<AudioTrack> &newTrack = releaseTracks[1];
    status_t status = NO_ERROR;

    {
        ALOGV("%s(%p)(soundID=%d, streamID=%d, leftVolume=%f, rightVolume=%f,"
                " priority=%d, loop=%d, rate=%f)",
                __func__, this, sound->getSoundID(), nextStreamID, leftVolume, rightVolume,
                priority, loop, rate);

        // initialize track
        const audio_stream_type_t streamType =
                AudioSystem::attributesToStreamType(*mStreamManager->getAttributes());
        const int32_t channelCount = sound->getChannelCount();
        const auto sampleRate = (uint32_t)lround(double(sound->getSampleRate()) * rate);
        size_t frameCount = 0;

        if (loop) {
            const audio_format_t format = sound->getFormat();
            const size_t frameSize = audio_is_linear_pcm(format)
                    ? channelCount * audio_bytes_per_sample(format) : 1;
            frameCount = sound->getSizeInBytes() / frameSize;
        }

        // check if the existing track has the same sound id.
        if (mAudioTrack != nullptr && mSoundID == sound->getSoundID()) {
            // the sample rate may fail to change if the audio track is a fast track.
            if (mAudioTrack->setSampleRate(sampleRate) == NO_ERROR) {
                newTrack = mAudioTrack;
                ALOGV("%s: reusing track %p for sound %d",
                        __func__, mAudioTrack.get(), sound->getSoundID());
            }
        }
        if (newTrack == nullptr) {
            // mToggle toggles each time a track is started on a given stream.
            // The toggle is concatenated with the Stream address and passed to AudioTrack
            // as callback user data. This enables the detection of callbacks received from the old
            // audio track while the new one is being started and avoids processing them with
            // wrong audio audio buffer size  (mAudioBufferSize)
            auto toggle = mToggle ^ 1;
            void* userData = (void*)((uintptr_t)this | toggle);
            audio_channel_mask_t soundChannelMask = sound->getChannelMask();
            // When sound contains a valid channel mask, use it as is.
            // Otherwise, use stream count to calculate channel mask.
            audio_channel_mask_t channelMask = soundChannelMask != AUDIO_CHANNEL_NONE
                    ? soundChannelMask : audio_channel_out_mask_from_count(channelCount);

            // do not create a new audio track if current track is compatible with sound parameters

            newTrack = new AudioTrack(streamType, sampleRate, sound->getFormat(),
                    channelMask, sound->getIMemory(), AUDIO_OUTPUT_FLAG_FAST,
                    staticCallback, userData,
                    0 /*default notification frames*/, AUDIO_SESSION_ALLOCATE,
                    AudioTrack::TRANSFER_DEFAULT,
                    nullptr /*offloadInfo*/, -1 /*uid*/, -1 /*pid*/,
                    mStreamManager->getAttributes(),
                    false /*doNotReconnect*/, 1.0f /*maxRequiredSpeed*/,
                    mStreamManager->getOpPackageName());
            // Set caller name so it can be logged in destructor.
            // MediaMetricsConstants.h: AMEDIAMETRICS_PROP_CALLERNAME_VALUE_SOUNDPOOL
            newTrack->setCallerName("soundpool");
            oldTrack = mAudioTrack;
            status = newTrack->initCheck();
            if (status != NO_ERROR) {
                ALOGE("%s: error creating AudioTrack", __func__);
                // newTrack goes out of scope, so reference count drops to zero
                goto exit;
            }
            // From now on, AudioTrack callbacks received with previous toggle value will be ignored.
            mToggle = toggle;
            mAudioTrack = newTrack;
            ALOGV("%s: using new track %p for sound %d",
                    __func__, newTrack.get(), sound->getSoundID());
        }
        if (mMuted) {
            newTrack->setVolume(0.0f, 0.0f);
        } else {
            newTrack->setVolume(leftVolume, rightVolume);
        }
        newTrack->setLoop(0, frameCount, loop);
        mAudioTrack->start();
        mSound = sound;
        mSoundID = sound->getSoundID();
        mPriority = priority;
        mLoop = loop;
        mLeftVolume = leftVolume;
        mRightVolume = rightVolume;
        mRate = rate;
        mState = PLAYING;
        mStopTimeNs = 0;
        mStreamID = nextStreamID;  // prefer this to be the last, as it is an atomic sync point
    }

exit:
    ALOGV("%s: delete oldTrack %p", __func__, oldTrack.get());
    if (status != NO_ERROR) {
        // TODO: should we consider keeping the soundID if the old track is OK?
        // Do not attempt to restart this track (should we remove the stream id?)
        mState = IDLE;
        mSoundID = 0;
        mSound.reset();
        mAudioTrack.clear();  // actual release from releaseTracks[]
    }
}

/* static */
void Stream::staticCallback(int event, void* user, void* info)
{
    const auto userAsInt = (uintptr_t)user;
    auto stream = reinterpret_cast<Stream*>(userAsInt & ~1);
    stream->callback(event, info, int(userAsInt & 1), 0 /* tries */);
}

void Stream::callback(int event, void* info, int toggle, int tries)
{
    int32_t activeStreamIDToRestart = 0;
    {
        std::unique_lock lock(mLock);
        ALOGV("%s track(%p) streamID %d", __func__, mAudioTrack.get(), (int)mStreamID);

        if (mAudioTrack == nullptr) {
            // The AudioTrack is either with this stream or its pair.
            // if this swaps a few times, the toggle is bound to be wrong, so we fail then.
            //
            // TODO: Modify AudioTrack callbacks to avoid the hacky toggle and retry
            // logic here.
            if (tries < 3) {
                lock.unlock();
                ALOGV("%s streamID %d going to pair stream", __func__, (int)mStreamID);
                getPairStream()->callback(event, info, toggle, tries + 1);
            } else {
                ALOGW("%s streamID %d cannot find track", __func__, (int)mStreamID);
            }
            return;
        }
        if (mToggle != toggle) {
            ALOGD("%s streamID %d wrong toggle", __func__, (int)mStreamID);
            return;
        }
        switch (event) {
        case AudioTrack::EVENT_MORE_DATA:
            ALOGW("%s streamID %d Invalid EVENT_MORE_DATA for static track",
                    __func__, (int)mStreamID);
            break;
        case AudioTrack::EVENT_UNDERRUN:
            ALOGW("%s streamID %d Invalid EVENT_UNDERRUN for static track",
                    __func__, (int)mStreamID);
            break;
        case AudioTrack::EVENT_BUFFER_END:
            ALOGV("%s streamID %d EVENT_BUFFER_END", __func__, (int)mStreamID);
            if (mState != IDLE) {
                activeStreamIDToRestart = mStreamID;
                mStopTimeNs = systemTime();
            }
            break;
        case AudioTrack::EVENT_LOOP_END:
            ALOGV("%s streamID %d EVENT_LOOP_END", __func__, (int)mStreamID);
            break;
        case AudioTrack::EVENT_NEW_IAUDIOTRACK:
            ALOGV("%s streamID %d NEW_IAUDIOTRACK", __func__, (int)mStreamID);
            break;
        default:
            ALOGW("%s streamID %d Invalid event %d", __func__, (int)mStreamID, event);
            break;
        }
    } // lock ends here.  This is on the callback thread, no need to be precise.
    if (activeStreamIDToRestart > 0) {
        // Restart only if a particular streamID is still current and active.
        ALOGV("%s: moveToRestartQueue %d", __func__, activeStreamIDToRestart);
        mStreamManager->moveToRestartQueue(this, activeStreamIDToRestart);
    }
}

void Stream::dump() const
{
    // TODO: consider std::try_lock() - ok for now for ALOGV.
    ALOGV("mPairStream=%p, mState=%d, mStreamID=%d, mSoundID=%d, mPriority=%d, mLoop=%d",
            getPairStream(), mState, (int)getStreamID(), getSoundID(), mPriority, mLoop);
}

} // namespace android::soundpool
