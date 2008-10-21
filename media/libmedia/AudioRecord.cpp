/*
**
** Copyright 2008, The Android Open Source Project
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

#define LOG_TAG "AudioRecord"

#include <stdint.h>
#include <sys/types.h>

#include <sched.h>
#include <sys/resource.h>

#include <private/media/AudioTrackShared.h>

#include <media/AudioSystem.h>
#include <media/AudioRecord.h>

#include <utils/IServiceManager.h>
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

AudioRecord::AudioRecord()
    : mStatus(NO_INIT)
{
}

AudioRecord::AudioRecord(
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

AudioRecord::~AudioRecord()
{
    if (mStatus == NO_ERROR) {
        if (mPosition) {
            releaseBuffer(&mAudioBuffer);
        }
        // obtainBuffer() will give up with an error
        mAudioRecord->stop();
        if (mClientRecordThread != 0) {
            mClientRecordThread->requestExitAndWait();
            mClientRecordThread.clear();
        }
        mAudioRecord.clear();
        IPCThreadState::self()->flushCommands();
    }
}

status_t AudioRecord::set(
        int streamType,
        uint32_t sampleRate,
        int format,
        int channelCount,
        int bufferCount,
        uint32_t flags,
        callback_t cbf, void* user)
{

    if (mAudioFlinger != 0) {
        return INVALID_OPERATION;
    }

    const sp<IAudioFlinger>& audioFlinger = AudioSystem::get_audio_flinger();
    if (audioFlinger == 0) {
        return NO_INIT;
    }

    if (streamType == DEFAULT_INPUT) {
        streamType = MIC_INPUT;
    }

    if (sampleRate == 0) {
        sampleRate = DEFAULT_SAMPLE_RATE;
    }
    // these below should probably come from the audioFlinger too...
    if (format == 0) {
        format = AudioSystem::PCM_16_BIT;
    }
    if (channelCount == 0) {
        channelCount = 1;
    }
    if (bufferCount == 0) {
        bufferCount = 2;
    } else if (bufferCount < 2) {
        return BAD_VALUE;
    }

    // validate parameters
    if (format != AudioSystem::PCM_16_BIT) {
        return BAD_VALUE;
    }
    if (channelCount != 1 && channelCount != 2) {
        return BAD_VALUE;
    }
    if (bufferCount < 2) {
        return BAD_VALUE;
    }

    // open record channel
    sp<IAudioRecord> record = audioFlinger->openRecord(getpid(), streamType,
            sampleRate, format, channelCount, bufferCount, flags);
    if (record == 0) {
        return NO_INIT;
    }
    sp<IMemory> cblk = record->getCblk();
    if (cblk == 0) {
        return NO_INIT;
    }
    if (cbf != 0) {
        mClientRecordThread = new ClientRecordThread(*this);
        if (mClientRecordThread == 0) {
            return NO_INIT;
        }
    }

    mStatus = NO_ERROR;

    mAudioFlinger = audioFlinger;
    mAudioRecord = record;
    mCblkMemory = cblk;
    mCblk = static_cast<audio_track_cblk_t*>(cblk->pointer());
    mCblk->buffers = (char*)mCblk + sizeof(audio_track_cblk_t);
    mSampleRate = sampleRate;
    mFrameCount = audioFlinger->frameCount();
    mFormat = format;
    mBufferCount = bufferCount;
    mChannelCount = channelCount;
    mActive = 0;
    mCbf = cbf;
    mUserData = user;
    mLatency = seconds(mFrameCount) / mSampleRate;
    mPosition = 0;
    return NO_ERROR;
}

status_t AudioRecord::initCheck() const
{
    return mStatus;
}

// -------------------------------------------------------------------------

nsecs_t AudioRecord::latency() const
{
    return mLatency;
}

uint32_t AudioRecord::sampleRate() const
{
    return mSampleRate;
}

int AudioRecord::format() const
{
    return mFormat;
}

int AudioRecord::channelCount() const
{
    return mChannelCount;
}

int AudioRecord::bufferCount() const
{
    return mBufferCount;
}

// -------------------------------------------------------------------------

status_t AudioRecord::start()
{
    status_t ret = NO_ERROR;
    
    // If using record thread, protect start sequence to make sure that
    // no stop command is processed before the thread is started
    if (mClientRecordThread != 0) {
        mRecordThreadLock.lock();        
    }

    if (android_atomic_or(1, &mActive) == 0) {
        setpriority(PRIO_PROCESS, 0, THREAD_PRIORITY_AUDIO_CLIENT);
        ret = mAudioRecord->start();
        if (ret == NO_ERROR) {
            if (mClientRecordThread != 0) {
                mClientRecordThread->run("ClientRecordThread", THREAD_PRIORITY_AUDIO_CLIENT);
            }
        }
    }
    
    if (mClientRecordThread != 0) {
        mRecordThreadLock.unlock();        
    }
    
    return ret;
}

status_t AudioRecord::stop()
{
    // If using record thread, protect stop sequence to make sure that
    // no start command is processed before requestExit() is called
    if (mClientRecordThread != 0) {
        mRecordThreadLock.lock();        
    }

    if (android_atomic_and(~1, &mActive) == 1) {
        if (mPosition) {
            mPosition = 0;
            releaseBuffer(&mAudioBuffer);
        }
        mAudioRecord->stop();
        setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_NORMAL);
        if (mClientRecordThread != 0) {
            mClientRecordThread->requestExit();
        }
    }
    
    if (mClientRecordThread != 0) {
        mRecordThreadLock.unlock();        
    }
    
    return NO_ERROR;
}

bool AudioRecord::stopped() const
{
    return !mActive;
}

// -------------------------------------------------------------------------

status_t AudioRecord::obtainBuffer(Buffer* audioBuffer, bool blocking)
{
    int active = mActive;
    int timeout = 0;
    status_t result;
    audio_track_cblk_t* cblk = mCblk;

    const uint32_t u = cblk->user;
    uint32_t s = cblk->server;

    if (u == s) {
        Mutex::Autolock _l(cblk->lock);
        goto start_loop_here;
        while (u == s) {
            active = mActive;
            if (UNLIKELY(!active))
                return NO_MORE_BUFFERS;
            if (UNLIKELY(!blocking))
                return WOULD_BLOCK;
            timeout = 0;
            result = cblk->cv.waitRelative(cblk->lock, seconds(1));
            if (__builtin_expect(result!=NO_ERROR, false)) {
                LOGW(   "obtainBuffer timed out (is the CPU pegged?) "
                        "user=%08x, server=%08x", u, s);
                timeout = 1;
            }
            // read the server count again
        start_loop_here:
            s = cblk->server;
        }
    }

    LOGW_IF(timeout,
        "*** SERIOUS WARNING *** obtainBuffer() timed out "
        "but didn't need to be locked. We recovered, but "
        "this shouldn't happen (user=%08x, server=%08x)", u, s);

    audioBuffer->flags       = 0;
    audioBuffer->channelCount= mChannelCount;
    audioBuffer->format      = mFormat;
    audioBuffer->frameCount  = mFrameCount;
    audioBuffer->size        = cblk->size;
    audioBuffer->raw         = (int8_t*)
            cblk->buffer(cblk->user & audio_track_cblk_t::BUFFER_MASK);
    return active ? status_t(NO_ERROR) : status_t(STOPPED);
}

void AudioRecord::releaseBuffer(Buffer* audioBuffer)
{
    // next buffer...
    if (UNLIKELY(mPosition)) {
        // clean the remaining part of the buffer
        size_t capacity = mAudioBuffer.size - mPosition;
        memset(mAudioBuffer.i8 + mPosition, 0, capacity);
    }
    audio_track_cblk_t* cblk = mCblk;
    cblk->stepUser(mBufferCount);
}

// -------------------------------------------------------------------------

ssize_t AudioRecord::read(void* buffer, size_t userSize)
{
    ssize_t read = 0;
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
        size_t toRead = userSize < capacity ? userSize : capacity;

        memcpy(buffer, mAudioBuffer.i8 + mPosition, toRead);

        buffer = static_cast<int8_t*>(buffer) + toRead;
        mPosition += toRead;
        userSize -= toRead;
        capacity -= toRead;
        read += toRead;

        if (capacity == 0) {
            mPosition = 0;
            releaseBuffer(&mAudioBuffer);
        }
    } while (userSize);

    return read;
}

// -------------------------------------------------------------------------

bool AudioRecord::processAudioBuffer(const sp<ClientRecordThread>& thread)
{
    Buffer audioBuffer;
    bool more;

    do {
        status_t err = obtainBuffer(&audioBuffer, true); 
        if (err < NO_ERROR) {
            LOGE("Error obtaining an audio buffer, giving up.");
            return false;
        }
        more = mCbf(mUserData, audioBuffer);
        releaseBuffer(&audioBuffer);
    } while (more && !thread->exitPending());

    // stop the track automatically
    this->stop();

    return true;
}

// =========================================================================

AudioRecord::ClientRecordThread::ClientRecordThread(AudioRecord& receiver)
    : Thread(false), mReceiver(receiver)
{
}

bool AudioRecord::ClientRecordThread::threadLoop()
{
    return mReceiver.processAudioBuffer(this);
}

// -------------------------------------------------------------------------

}; // namespace android

