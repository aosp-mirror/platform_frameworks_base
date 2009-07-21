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

//#define LOG_NDEBUG 0
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
        int inputSource,
        uint32_t sampleRate,
        int format,
        int channelCount,
        int frameCount,
        uint32_t flags,
        callback_t cbf,
        void* user,
        int notificationFrames)
    : mStatus(NO_INIT)
{
    mStatus = set(inputSource, sampleRate, format, channelCount,
            frameCount, flags, cbf, user, notificationFrames);
}

AudioRecord::~AudioRecord()
{
    if (mStatus == NO_ERROR) {
        // Make sure that callback function exits in the case where
        // it is looping on buffer empty condition in obtainBuffer().
        // Otherwise the callback thread will never exit.
        stop();
        if (mClientRecordThread != 0) {
            mClientRecordThread->requestExitAndWait();
            mClientRecordThread.clear();
        }
        mAudioRecord.clear();
        IPCThreadState::self()->flushCommands();
    }
}

status_t AudioRecord::set(
        int inputSource,
        uint32_t sampleRate,
        int format,
        int channelCount,
        int frameCount,
        uint32_t flags,
        callback_t cbf,
        void* user,
        int notificationFrames,
        bool threadCanCallJava)
{

    LOGV("set(): sampleRate %d, channelCount %d, frameCount %d",sampleRate, channelCount, frameCount);
    if (mAudioRecord != 0) {
        return INVALID_OPERATION;
    }

    const sp<IAudioFlinger>& audioFlinger = AudioSystem::get_audio_flinger();
    if (audioFlinger == 0) {
        return NO_INIT;
    }

    if (inputSource == DEFAULT_INPUT) {
        inputSource = MIC_INPUT;
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

    // validate parameters
    if (format != AudioSystem::PCM_16_BIT) {
        return BAD_VALUE;
    }
    if (channelCount != 1 && channelCount != 2) {
        return BAD_VALUE;
    }

    // validate framecount
    size_t inputBuffSizeInBytes = -1;
    if (AudioSystem::getInputBufferSize(sampleRate, format, channelCount, &inputBuffSizeInBytes)
            != NO_ERROR) {
        LOGE("AudioSystem could not query the input buffer size.");
        return NO_INIT;    
    }
    if (inputBuffSizeInBytes == 0) {
        LOGE("Recording parameters are not supported: sampleRate %d, channelCount %d, format %d",
            sampleRate, channelCount, format);
        return BAD_VALUE;
    }
    int frameSizeInBytes = channelCount * (format == AudioSystem::PCM_16_BIT ? 2 : 1);

    // We use 2* size of input buffer for ping pong use of record buffer.
    int minFrameCount = 2 * inputBuffSizeInBytes / frameSizeInBytes;
    LOGV("AudioRecord::set() minFrameCount = %d", minFrameCount);

    if (frameCount == 0) {
        frameCount = minFrameCount;
    } else if (frameCount < minFrameCount) {
        return BAD_VALUE;
    }

    if (notificationFrames == 0) {
        notificationFrames = frameCount/2;
    }

    // open record channel
    status_t status;
    sp<IAudioRecord> record = audioFlinger->openRecord(getpid(), inputSource,
                                                       sampleRate, format,
                                                       channelCount,
                                                       frameCount,
                                                       ((uint16_t)flags) << 16, 
                                                       &status);
    if (record == 0) {
        LOGE("AudioFlinger could not create record track, status: %d", status);
        return status;
    }
    sp<IMemory> cblk = record->getCblk();
    if (cblk == 0) {
        return NO_INIT;
    }
    if (cbf != 0) {
        mClientRecordThread = new ClientRecordThread(*this, threadCanCallJava);
        if (mClientRecordThread == 0) {
            return NO_INIT;
        }
    }

    mStatus = NO_ERROR;

    mAudioRecord = record;
    mCblkMemory = cblk;
    mCblk = static_cast<audio_track_cblk_t*>(cblk->pointer());
    mCblk->buffers = (char*)mCblk + sizeof(audio_track_cblk_t);
    mCblk->out = 0;
    mFormat = format;
    // Update buffer size in case it has been limited by AudioFlinger during track creation
    mFrameCount = mCblk->frameCount;
    mChannelCount = channelCount;
    mActive = 0;
    mCbf = cbf;
    mNotificationFrames = notificationFrames;
    mRemainingFrames = notificationFrames;
    mUserData = user;
    // TODO: add audio hardware input latency here
    mLatency = (1000*mFrameCount) / sampleRate;
    mMarkerPosition = 0;
    mMarkerReached = false;
    mNewPosition = 0;
    mUpdatePeriod = 0;
    mInputSource = (uint8_t)inputSource;

    return NO_ERROR;
}

status_t AudioRecord::initCheck() const
{
    return mStatus;
}

// -------------------------------------------------------------------------

uint32_t AudioRecord::latency() const
{
    return mLatency;
}

int AudioRecord::format() const
{
    return mFormat;
}

int AudioRecord::channelCount() const
{
    return mChannelCount;
}

uint32_t AudioRecord::frameCount() const
{
    return mFrameCount;
}

int AudioRecord::frameSize() const
{
    return channelCount()*((format() == AudioSystem::PCM_8_BIT) ? sizeof(uint8_t) : sizeof(int16_t));
}

int AudioRecord::inputSource() const
{
    return (int)mInputSource;
}

// -------------------------------------------------------------------------

status_t AudioRecord::start()
{
    status_t ret = NO_ERROR;
    sp<ClientRecordThread> t = mClientRecordThread;

    LOGV("start");

    if (t != 0) {
        if (t->exitPending()) {
            if (t->requestExitAndWait() == WOULD_BLOCK) {
                LOGE("AudioRecord::start called from thread");
                return WOULD_BLOCK;
            }
        }
        t->mLock.lock();
     }

    if (android_atomic_or(1, &mActive) == 0) {
        mNewPosition = mCblk->user + mUpdatePeriod;
        mCblk->bufferTimeoutMs = MAX_RUN_TIMEOUT_MS;
        mCblk->waitTimeMs = 0;
        if (t != 0) {
           t->run("ClientRecordThread", THREAD_PRIORITY_AUDIO_CLIENT);
        } else {
            setpriority(PRIO_PROCESS, 0, THREAD_PRIORITY_AUDIO_CLIENT);
        }
        ret = mAudioRecord->start();
    }

    if (t != 0) {
        t->mLock.unlock();
    }

    return ret;
}

status_t AudioRecord::stop()
{
    sp<ClientRecordThread> t = mClientRecordThread;

    LOGV("stop");

    if (t != 0) {
        t->mLock.lock();
     }

    if (android_atomic_and(~1, &mActive) == 1) {
        mCblk->cv.signal();
        mAudioRecord->stop();
        // the record head position will reset to 0, so if a marker is set, we need
        // to activate it again
        mMarkerReached = false;
        if (t != 0) {
            t->requestExit();
        } else {
            setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_NORMAL);
        }
    }

    if (t != 0) {
        t->mLock.unlock();
    }

    return NO_ERROR;
}

bool AudioRecord::stopped() const
{
    return !mActive;
}

uint32_t AudioRecord::getSampleRate()
{
    return mCblk->sampleRate;
}

status_t AudioRecord::setMarkerPosition(uint32_t marker)
{
    if (mCbf == 0) return INVALID_OPERATION;

    mMarkerPosition = marker;
    mMarkerReached = false;

    return NO_ERROR;
}

status_t AudioRecord::getMarkerPosition(uint32_t *marker)
{
    if (marker == 0) return BAD_VALUE;

    *marker = mMarkerPosition;

    return NO_ERROR;
}

status_t AudioRecord::setPositionUpdatePeriod(uint32_t updatePeriod)
{
    if (mCbf == 0) return INVALID_OPERATION;

    uint32_t curPosition;
    getPosition(&curPosition);
    mNewPosition = curPosition + updatePeriod;
    mUpdatePeriod = updatePeriod;

    return NO_ERROR;
}

status_t AudioRecord::getPositionUpdatePeriod(uint32_t *updatePeriod)
{
    if (updatePeriod == 0) return BAD_VALUE;

    *updatePeriod = mUpdatePeriod;

    return NO_ERROR;
}

status_t AudioRecord::getPosition(uint32_t *position)
{
    if (position == 0) return BAD_VALUE;

    *position = mCblk->user;

    return NO_ERROR;
}


// -------------------------------------------------------------------------

status_t AudioRecord::obtainBuffer(Buffer* audioBuffer, int32_t waitCount)
{
    int active;
    int timeout = 0;
    status_t result;
    audio_track_cblk_t* cblk = mCblk;
    uint32_t framesReq = audioBuffer->frameCount;
    uint32_t waitTimeMs = (waitCount < 0) ? cblk->bufferTimeoutMs : WAIT_PERIOD_MS;

    audioBuffer->frameCount  = 0;
    audioBuffer->size        = 0;

    uint32_t framesReady = cblk->framesReady();

    if (framesReady == 0) {
        Mutex::Autolock _l(cblk->lock);
        goto start_loop_here;
        while (framesReady == 0) {
            active = mActive;
            if (UNLIKELY(!active))
                return NO_MORE_BUFFERS;
            if (UNLIKELY(!waitCount))
                return WOULD_BLOCK;
            timeout = 0;
            result = cblk->cv.waitRelative(cblk->lock, milliseconds(waitTimeMs));
            if (__builtin_expect(result!=NO_ERROR, false)) {
                cblk->waitTimeMs += waitTimeMs;
                if (cblk->waitTimeMs >= cblk->bufferTimeoutMs) {
                    LOGW(   "obtainBuffer timed out (is the CPU pegged?) "
                            "user=%08x, server=%08x", cblk->user, cblk->server);
                    timeout = 1;
                    cblk->waitTimeMs = 0;
                }
                if (--waitCount == 0) {
                    return TIMED_OUT;
                }
            }
            // read the server count again
        start_loop_here:
            framesReady = cblk->framesReady();
        }
    }

    LOGW_IF(timeout,
        "*** SERIOUS WARNING *** obtainBuffer() timed out "
        "but didn't need to be locked. We recovered, but "
        "this shouldn't happen (user=%08x, server=%08x)", cblk->user, cblk->server);

    cblk->waitTimeMs = 0;
    
    if (framesReq > framesReady) {
        framesReq = framesReady;
    }

    uint32_t u = cblk->user;
    uint32_t bufferEnd = cblk->userBase + cblk->frameCount;

    if (u + framesReq > bufferEnd) {
        framesReq = bufferEnd - u;
    }

    audioBuffer->flags       = 0;
    audioBuffer->channelCount= mChannelCount;
    audioBuffer->format      = mFormat;
    audioBuffer->frameCount  = framesReq;
    audioBuffer->size        = framesReq*mChannelCount*sizeof(int16_t);
    audioBuffer->raw         = (int8_t*)cblk->buffer(u);
    active = mActive;
    return active ? status_t(NO_ERROR) : status_t(STOPPED);
}

void AudioRecord::releaseBuffer(Buffer* audioBuffer)
{
    audio_track_cblk_t* cblk = mCblk;
    cblk->stepUser(audioBuffer->frameCount);
}

// -------------------------------------------------------------------------

ssize_t AudioRecord::read(void* buffer, size_t userSize)
{
    ssize_t read = 0;
    Buffer audioBuffer;
    int8_t *dst = static_cast<int8_t*>(buffer);

    if (ssize_t(userSize) < 0) {
        // sanity-check. user is most-likely passing an error code.
        LOGE("AudioRecord::read(buffer=%p, size=%u (%d)",
                buffer, userSize, userSize);
        return BAD_VALUE;
    }

    LOGV("read size: %d", userSize);

    do {

        audioBuffer.frameCount = userSize/mChannelCount/sizeof(int16_t);

        // Calling obtainBuffer() with a negative wait count causes
        // an (almost) infinite wait time.
        status_t err = obtainBuffer(&audioBuffer, -1);
        if (err < 0) {
            // out of buffers, return #bytes written
            if (err == status_t(NO_MORE_BUFFERS))
                break;
            return ssize_t(err);
        }

        size_t bytesRead = audioBuffer.size;
        memcpy(dst, audioBuffer.i8, bytesRead);

        dst += bytesRead;
        userSize -= bytesRead;
        read += bytesRead;

        releaseBuffer(&audioBuffer);
    } while (userSize);

    return read;
}

// -------------------------------------------------------------------------

bool AudioRecord::processAudioBuffer(const sp<ClientRecordThread>& thread)
{
    Buffer audioBuffer;
    uint32_t frames = mRemainingFrames;
    size_t readSize;

    // Manage marker callback
    if (!mMarkerReached && (mMarkerPosition > 0)) {
        if (mCblk->user >= mMarkerPosition) {
            mCbf(EVENT_MARKER, mUserData, (void *)&mMarkerPosition);
            mMarkerReached = true;
        }
    }

    // Manage new position callback
    if (mUpdatePeriod > 0) {
        while (mCblk->user >= mNewPosition) {
            mCbf(EVENT_NEW_POS, mUserData, (void *)&mNewPosition);
            mNewPosition += mUpdatePeriod;
        }
    }

    do {
        audioBuffer.frameCount = frames;
        // Calling obtainBuffer() with a wait count of 1 
        // limits wait time to WAIT_PERIOD_MS. This prevents from being 
        // stuck here not being able to handle timed events (position, markers).
        status_t err = obtainBuffer(&audioBuffer, 1);
        if (err < NO_ERROR) {
            if (err != TIMED_OUT) {
                LOGE_IF(err != status_t(NO_MORE_BUFFERS), "Error obtaining an audio buffer, giving up.");
                return false;
            }
            break;
        }
        if (err == status_t(STOPPED)) return false;

        size_t reqSize = audioBuffer.size;
        mCbf(EVENT_MORE_DATA, mUserData, &audioBuffer);
        readSize = audioBuffer.size;

        // Sanity check on returned size
        if (ssize_t(readSize) <= 0) {
            // The callback is done filling buffers
            // Keep this thread going to handle timed events and
            // still try to get more data in intervals of WAIT_PERIOD_MS
            // but don't just loop and block the CPU, so wait
            usleep(WAIT_PERIOD_MS*1000);
            break;
        }
        if (readSize > reqSize) readSize = reqSize;

        audioBuffer.size = readSize;
        audioBuffer.frameCount = readSize/mChannelCount/sizeof(int16_t);
        frames -= audioBuffer.frameCount;

        releaseBuffer(&audioBuffer);

    } while (frames);

    
    // Manage overrun callback
    if (mActive && (mCblk->framesAvailable_l() == 0)) {
        LOGV("Overrun user: %x, server: %x, flowControlFlag %d", mCblk->user, mCblk->server, mCblk->flowControlFlag);
        if (mCblk->flowControlFlag == 0) {
            mCbf(EVENT_OVERRUN, mUserData, 0);
            mCblk->flowControlFlag = 1;
        }
    }

    if (frames == 0) {
        mRemainingFrames = mNotificationFrames;
    } else {
        mRemainingFrames = frames;
    }
    return true;
}

// =========================================================================

AudioRecord::ClientRecordThread::ClientRecordThread(AudioRecord& receiver, bool bCanCallJava)
    : Thread(bCanCallJava), mReceiver(receiver)
{
}

bool AudioRecord::ClientRecordThread::threadLoop()
{
    return mReceiver.processAudioBuffer(this);
}

// -------------------------------------------------------------------------

}; // namespace android

