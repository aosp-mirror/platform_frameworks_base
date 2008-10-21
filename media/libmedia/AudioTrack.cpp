/* //device/extlibs/pv/android/AudioTrack.cpp
**
** Copyright 2007, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License"); 
** you may not use this file except in compliance with the License. 
** You may obtain a copy of the License at 
**
**     http://www.apache.org/licenses/LICENSE-2.0 
**
** Unless required by applicable law or agreed to in writing, software 
** distributed under the License is distributed on an "AS IS" BASIS, 
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. 
** See the License for the specific language governing permissions and 
** limitations under the License.
*/


//#define LOG_NDEBUG 0
#define LOG_TAG "AudioTrack"

#include <stdint.h>
#include <sys/types.h>

#include <sched.h>
#include <sys/resource.h>

#include <private/media/AudioTrackShared.h>

#include <media/AudioSystem.h>
#include <media/AudioTrack.h>

#include <utils/Log.h>
#include <utils/MemoryDealer.h>
#include <utils/Parcel.h>
#include <utils/IPCThreadState.h>
#include <utils/Timers.h>
#include <cutils/atomic.h>

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))

namespace android {

// ---------------------------------------------------------------------------

static volatile size_t gFrameCount = 0;

size_t AudioTrack::frameCount()
{
    if (gFrameCount) return gFrameCount;
    const sp<IAudioFlinger>& af = AudioSystem::get_audio_flinger();
    if (af == 0) return PERMISSION_DENIED;
    gFrameCount = af->frameCount();
    return gFrameCount;
}

// ---------------------------------------------------------------------------

AudioTrack::AudioTrack()
    : mStatus(NO_INIT)
{
}

AudioTrack::AudioTrack(
        int streamType,
        uint32_t sampleRate,
        int format,
        int channelCount,
        int bufferCount,
        uint32_t flags,
        callback_t cbf, void* user)
    : mStatus(NO_INIT)
{
    mStatus = set(streamType, sampleRate, format, channelCount,
            bufferCount, flags, cbf, user);
}

AudioTrack::~AudioTrack()
{
    if (mStatus == NO_ERROR) {
        if (mPosition) {
            releaseBuffer(&mAudioBuffer);
        }
        // obtainBuffer() will give up with an error
        mAudioTrack->stop();
        if (mAudioTrackThread != 0) {
            mAudioTrackThread->requestExitAndWait();
            mAudioTrackThread.clear();
        }
        mAudioTrack.clear();
        IPCThreadState::self()->flushCommands();
    }
}

status_t AudioTrack::set(
        int streamType,
        uint32_t sampleRate,
        int format,
        int channelCount,
        int bufferCount,
        uint32_t flags,
        callback_t cbf, void* user)
{

    if (mAudioFlinger != 0) {
        LOGE("Track already in use");
        return INVALID_OPERATION;
    }

    const sp<IAudioFlinger>& audioFlinger = AudioSystem::get_audio_flinger();
    if (audioFlinger == 0) {
       LOGE("Could not get audioflinger");
       return NO_INIT;
    }

    // handle default values first.
    if (streamType == DEFAULT) {
        streamType = MUSIC;
    }
    if (sampleRate == 0) {
        sampleRate = audioFlinger->sampleRate();
    }
    // these below should probably come from the audioFlinger too...
    if (format == 0) {
        format = AudioSystem::PCM_16_BIT;
    }
    if (channelCount == 0) {
        channelCount = 2;
    }
    if (bufferCount == 0) {
        bufferCount = 2;
    }

    // validate parameters
    if (format != AudioSystem::PCM_16_BIT) {
        LOGE("Invalid format");
        return BAD_VALUE;
    }
    if (channelCount != 1 && channelCount != 2) {
        LOGE("Invalid channel number");
        return BAD_VALUE;
    }
    if (bufferCount < 2) {
       LOGE("Invalid buffer count");
       return BAD_VALUE;
    }

    // create the track
    sp<IAudioTrack> track = audioFlinger->createTrack(getpid(),
            streamType, sampleRate, format, channelCount, bufferCount, flags);
    if (track == 0) {
        LOGE("AudioFlinger could not create track");
        return NO_INIT;
    }
    sp<IMemory> cblk = track->getCblk();
    if (cblk == 0) {
        LOGE("Could not get control block");
        return NO_INIT;
    }
    if (cbf != 0) {
        mAudioTrackThread = new AudioTrackThread(*this);
        if (mAudioTrackThread == 0) {
          LOGE("Could not create callback thread");
          return NO_INIT;
        }
    }

    mStatus = NO_ERROR;

    mAudioFlinger = audioFlinger;
    mAudioTrack = track;
    mCblkMemory = cblk;
    mCblk = static_cast<audio_track_cblk_t*>(cblk->pointer());
    mCblk->buffers = (char*)mCblk + sizeof(audio_track_cblk_t);
    mCblk->volume[0] = mCblk->volume[1] = 0x1000;
    mVolume[LEFT] = 1.0f;
    mVolume[RIGHT] = 1.0f;
    mSampleRate = sampleRate;
    mFrameCount = audioFlinger->frameCount();
    mStreamType = streamType;
    mFormat = format;
    mBufferCount = bufferCount;
    mChannelCount = channelCount;
    mMuted = false;
    mActive = 0;
    mReserved = 0;
    mCbf = cbf;
    mUserData = user;
    mLatency = seconds(mFrameCount) / mSampleRate;
    mPosition = 0;
    return NO_ERROR;
}

status_t AudioTrack::initCheck() const
{
    return mStatus;
}

// -------------------------------------------------------------------------

nsecs_t AudioTrack::latency() const
{
    return mLatency;
}

int AudioTrack::streamType() const
{
    return mStreamType;
}

uint32_t AudioTrack::sampleRate() const
{
    return mSampleRate;
}

int AudioTrack::format() const
{
    return mFormat;
}

int AudioTrack::channelCount() const
{
    return mChannelCount;
}

int AudioTrack::bufferCount() const
{
    return mBufferCount;
}

// -------------------------------------------------------------------------

void AudioTrack::start()
{
    sp<AudioTrackThread> t = mAudioTrackThread;

    LOGV("start");
    if (t != 0) {
        if (t->exitPending()) {
            if (t->requestExitAndWait() == WOULD_BLOCK) {
                LOGE("AudioTrack::start called from thread");
                return;
            }
        }
        t->mLock.lock();
     }

    if (android_atomic_or(1, &mActive) == 0) {
        if (t != 0) {
           t->run("AudioTrackThread", THREAD_PRIORITY_AUDIO_CLIENT);
        } else {
            setpriority(PRIO_PROCESS, 0, THREAD_PRIORITY_AUDIO_CLIENT);
        }
        mAudioTrack->start();
    }

    if (t != 0) {
        t->mLock.unlock();
    }
}

void AudioTrack::stop()
{
    sp<AudioTrackThread> t = mAudioTrackThread;

    LOGV("stop");
    if (t != 0) {
        t->mLock.lock();
    }

    if (android_atomic_and(~1, &mActive) == 1) {
        if (mPosition) {
            releaseBuffer(&mAudioBuffer);
        }
        mAudioTrack->stop();
       if (t != 0) {
           t->requestExit();
       } else {
           setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_NORMAL);
       }
    }

    if (t != 0) {
        t->mLock.unlock();
    }
}

bool AudioTrack::stopped() const
{
    return !mActive;
}

void AudioTrack::flush()
{
    LOGV("flush");
    if (!mActive) {
        mCblk->lock.lock();
        mAudioTrack->flush();
        // Release AudioTrack callback thread in case it was waiting for new buffers
        // in AudioTrack::obtainBuffer()
        mCblk->cv.signal();
        mCblk->lock.unlock();
    }
}

void AudioTrack::pause()
{
    LOGV("pause");
    if (android_atomic_and(~1, &mActive) == 1) {
        mActive = 0;
        mAudioTrack->pause();
    }
}

void AudioTrack::mute(bool e)
{
    mAudioTrack->mute(e);
    mMuted = e;
}

bool AudioTrack::muted() const
{
    return mMuted;
}

void AudioTrack::setVolume(float left, float right)
{
    mVolume[LEFT] = left;
    mVolume[RIGHT] = right;

    // write must be atomic
    mCblk->volumeLR = (int32_t(int16_t(left * 0x1000)) << 16) | int16_t(right * 0x1000);
}

void AudioTrack::getVolume(float* left, float* right)
{
    *left  = mVolume[LEFT];
    *right = mVolume[RIGHT];
}

void AudioTrack::setSampleRate(int rate)
{
    if (rate > MAX_SAMPLE_RATE) rate = MAX_SAMPLE_RATE;
    mCblk->sampleRate = rate;
}

uint32_t AudioTrack::getSampleRate()
{
    return uint32_t(mCblk->sampleRate);
}

// -------------------------------------------------------------------------

status_t AudioTrack::obtainBuffer(Buffer* audioBuffer, bool blocking)
{
    int active;
    int timeout = 0;
    status_t result;
    audio_track_cblk_t* cblk = mCblk;

    uint32_t u = cblk->user;
    uint32_t u_seq = u & audio_track_cblk_t::SEQUENCE_MASK;
    uint32_t u_buf = u & audio_track_cblk_t::BUFFER_MASK;

    uint32_t s = cblk->server;
    uint32_t s_seq = s & audio_track_cblk_t::SEQUENCE_MASK;
    uint32_t s_buf = s & audio_track_cblk_t::BUFFER_MASK;

    LOGW_IF(u_seq < s_seq, "user doesn't fill buffers fast enough");

    if (u_seq > s_seq && u_buf == s_buf) {
        Mutex::Autolock _l(cblk->lock);
        goto start_loop_here;
        while (u_seq > s_seq && u_buf == s_buf) {
            active = mActive;
            if (UNLIKELY(!active)) {
                LOGV("Not active and NO_MORE_BUFFERS");
                return NO_MORE_BUFFERS;
            }
            if (UNLIKELY(!blocking))
                return WOULD_BLOCK;
            timeout = 0;
            result = cblk->cv.waitRelative(cblk->lock, seconds(1));
            if (__builtin_expect(result!=NO_ERROR, false)) {
                LOGW(   "obtainBuffer timed out (is the CPU pegged?) "
                        "user=%08x, server=%08x", u, s);
                mAudioTrack->start(); // FIXME: Wake up audioflinger
                timeout = 1;
            }
            // Read user count in case a flush has reset while we where waiting on cv.
            u = cblk->user;
            u_seq = u & audio_track_cblk_t::SEQUENCE_MASK;
            u_buf = u & audio_track_cblk_t::BUFFER_MASK;

            // read the server count again
        start_loop_here:
            s = cblk->server;
            s_seq = s & audio_track_cblk_t::SEQUENCE_MASK;
            s_buf = s & audio_track_cblk_t::BUFFER_MASK;
        }
    }

    LOGW_IF(timeout,
        "*** SERIOUS WARNING *** obtainBuffer() timed out "
        "but didn't need to be locked. We recovered, but "
        "this shouldn't happen (user=%08x, server=%08x)", u, s);

    audioBuffer->flags       = mMuted ? Buffer::MUTE : 0;
    audioBuffer->channelCount= mChannelCount;
    audioBuffer->format      = mFormat;
    audioBuffer->frameCount  = mFrameCount;
    audioBuffer->size        = cblk->size;
    audioBuffer->raw         = (int8_t *)cblk->buffer(u_buf);
    active = mActive;
    return active ? status_t(NO_ERROR) : status_t(STOPPED);
}

void AudioTrack::releaseBuffer(Buffer* audioBuffer)
{
    // next buffer...
    if (UNLIKELY(mPosition)) {
        // clean the remaining part of the buffer
        size_t capacity = mAudioBuffer.size - mPosition;
        memset(mAudioBuffer.i8 + mPosition, 0, capacity);
        mPosition = 0;
    }
    audio_track_cblk_t* cblk = mCblk;
    cblk->stepUser(mBufferCount);
}

// -------------------------------------------------------------------------

ssize_t AudioTrack::write(const void* buffer, size_t userSize)
{
    if (ssize_t(userSize) < 0) {
        // sanity-check. user is most-likely passing an error code.
        LOGE("AudioTrack::write(buffer=%p, size=%u (%d)", 
                buffer, userSize, userSize);
        return BAD_VALUE;
    }

    LOGV("write %d bytes, mActive=%d", userSize, mActive);
    ssize_t written = 0;
    do {
        if (mPosition == 0) {
            status_t err = obtainBuffer(&mAudioBuffer, true);
            if (err < 0) {
                // out of buffers, return #bytes written
                if (err == status_t(NO_MORE_BUFFERS))
                    break;
                return ssize_t(err);
            }
        }

        size_t capacity = mAudioBuffer.size - mPosition;
        size_t toWrite = userSize < capacity ? userSize : capacity;

        memcpy(mAudioBuffer.i8 + mPosition, buffer, toWrite);
        buffer = static_cast<const int8_t*>(buffer) + toWrite;
        mPosition += toWrite;
        userSize -= toWrite;
        capacity -= toWrite;
        written += toWrite;

        if (capacity == 0) {
            mPosition = 0;
            releaseBuffer(&mAudioBuffer);
        }
    } while (userSize);

    return written;
}

// -------------------------------------------------------------------------

bool AudioTrack::processAudioBuffer(const sp<AudioTrackThread>& thread)
{
    Buffer audioBuffer;

    status_t err = obtainBuffer(&audioBuffer, true);
    if (err < NO_ERROR) {
        LOGE("Error obtaining an audio buffer, giving up.");
        return false;
    }
    if (err == status_t(STOPPED)) return false;
    mCbf(mUserData, audioBuffer);
    releaseBuffer(&audioBuffer);

    return true;
}

status_t AudioTrack::dump(int fd, const Vector<String16>& args) const
{

    const size_t SIZE = 256;
    char buffer[SIZE];
    String8 result;

    result.append(" AudioTrack::dump\n");
    snprintf(buffer, 255, "  stream type(%d), left - right volume(%f, %f)\n", mStreamType, mVolume[0], mVolume[1]);
    result.append(buffer);
    snprintf(buffer, 255, "  format(%d), channel count(%d), frame count(%d), buffer count(%d)\n", mFormat, mChannelCount, mFrameCount, mBufferCount);
    result.append(buffer);
    snprintf(buffer, 255, "  sample rate(%d), status(%d), muted(%d), reserved(%d)\n", mSampleRate, mStatus, mMuted, mReserved);
    result.append(buffer);
    snprintf(buffer, 255, "  active(%d), latency (%lld), position(%d)\n", mActive, mLatency, mPosition);
    result.append(buffer);
    ::write(fd, result.string(), result.size());
    return NO_ERROR;
}

// =========================================================================

AudioTrack::AudioTrackThread::AudioTrackThread(AudioTrack& receiver)
    : Thread(false), mReceiver(receiver)
{
}

bool AudioTrack::AudioTrackThread::threadLoop()
{
    return mReceiver.processAudioBuffer(this);
}

status_t AudioTrack::AudioTrackThread::readyToRun()
{
    return NO_ERROR;
}

void AudioTrack::AudioTrackThread::onFirstRef()
{
}

// =========================================================================

audio_track_cblk_t::audio_track_cblk_t()
    : user(0), server(0), volumeLR(0), buffers(0), size(0)
{
}

uint32_t audio_track_cblk_t::stepUser(int bufferCount)
{
    uint32_t u = this->user;
    uint32_t u_seq = u & audio_track_cblk_t::SEQUENCE_MASK;
    uint32_t u_buf = u & audio_track_cblk_t::BUFFER_MASK;
    if (++u_buf >= uint32_t(bufferCount)) {
        u_seq += 0x100;
        u_buf = 0;
    }
    u = u_seq | u_buf;
    this->user = u; 
    return u;
}

bool audio_track_cblk_t::stepServer(int bufferCount)
{
    // the code below simulates lock-with-timeout
    // we MUST do this to protect the AudioFlinger server
    // as this lock is shared with the client.
    status_t err;

    err = lock.tryLock();
    if (err == -EBUSY) { // just wait a bit
        usleep(1000);
        err = lock.tryLock();
    }
    if (err != NO_ERROR) {
        // probably, the client just died.
        return false;
    }

    uint32_t s = this->server;
    uint32_t s_seq = s & audio_track_cblk_t::SEQUENCE_MASK;
    uint32_t s_buf = s & audio_track_cblk_t::BUFFER_MASK;
    s_buf++;
    if (s_buf >= uint32_t(bufferCount)) {
        s_seq += 0x100;
        s_buf = 0;
    }
    s = s_seq | s_buf;

    this->server = s; 
    cv.signal();
    lock.unlock();
    return true;
}

void* audio_track_cblk_t::buffer(int id) const
{
    return (char*)this->buffers + id * this->size;
}

// -------------------------------------------------------------------------

}; // namespace android

