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
#include <media/mediarecorder.h>

#include <binder/IServiceManager.h>
#include <utils/Log.h>
#include <binder/Parcel.h>
#include <binder/IPCThreadState.h>
#include <utils/Timers.h>
#include <cutils/atomic.h>

#define LIKELY( exp )       (__builtin_expect( (exp) != 0, true  ))
#define UNLIKELY( exp )     (__builtin_expect( (exp) != 0, false ))

namespace android {
// ---------------------------------------------------------------------------

// static
status_t AudioRecord::getMinFrameCount(
        int* frameCount,
        uint32_t sampleRate,
        int format,
        int channelCount)
{
    size_t size = 0;
    if (AudioSystem::getInputBufferSize(sampleRate, format, channelCount, &size)
            != NO_ERROR) {
        LOGE("AudioSystem could not query the input buffer size.");
        return NO_INIT;
    }

    if (size == 0) {
        LOGE("Unsupported configuration: sampleRate %d, format %d, channelCount %d",
            sampleRate, format, channelCount);
        return BAD_VALUE;
    }

    // We double the size of input buffer for ping pong use of record buffer.
    size <<= 1;

    if (AudioSystem::isLinearPCM(format)) {
        size /= channelCount * (format == AudioSystem::PCM_16_BIT ? 2 : 1);
    }

    *frameCount = size;
    return NO_ERROR;
}

// ---------------------------------------------------------------------------

AudioRecord::AudioRecord()
    : mStatus(NO_INIT), mSessionId(0)
{
}

AudioRecord::AudioRecord(
        int inputSource,
        uint32_t sampleRate,
        int format,
        uint32_t channels,
        int frameCount,
        uint32_t flags,
        callback_t cbf,
        void* user,
        int notificationFrames,
        int sessionId)
    : mStatus(NO_INIT), mSessionId(0)
{
    mStatus = set(inputSource, sampleRate, format, channels,
            frameCount, flags, cbf, user, notificationFrames, sessionId);
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
        uint32_t channels,
        int frameCount,
        uint32_t flags,
        callback_t cbf,
        void* user,
        int notificationFrames,
        bool threadCanCallJava,
        int sessionId)
{

    LOGV("set(): sampleRate %d, channels %d, frameCount %d",sampleRate, channels, frameCount);
    if (mAudioRecord != 0) {
        return INVALID_OPERATION;
    }

    if (inputSource == AUDIO_SOURCE_DEFAULT) {
        inputSource = AUDIO_SOURCE_MIC;
    }

    if (sampleRate == 0) {
        sampleRate = DEFAULT_SAMPLE_RATE;
    }
    // these below should probably come from the audioFlinger too...
    if (format == 0) {
        format = AudioSystem::PCM_16_BIT;
    }
    // validate parameters
    if (!AudioSystem::isValidFormat(format)) {
        LOGE("Invalid format");
        return BAD_VALUE;
    }

    if (!AudioSystem::isInputChannel(channels)) {
        return BAD_VALUE;
    }

    int channelCount = AudioSystem::popCount(channels);

    audio_io_handle_t input = AudioSystem::getInput(inputSource,
                                    sampleRate, format, channels, (AudioSystem::audio_in_acoustics)flags);
    if (input == 0) {
        LOGE("Could not get audio input for record source %d", inputSource);
        return BAD_VALUE;
    }

    // validate framecount
    int minFrameCount = 0;
    status_t status = getMinFrameCount(&minFrameCount, sampleRate, format, channelCount);
    if (status != NO_ERROR) {
        return status;
    }
    LOGV("AudioRecord::set() minFrameCount = %d", minFrameCount);

    if (frameCount == 0) {
        frameCount = minFrameCount;
    } else if (frameCount < minFrameCount) {
        return BAD_VALUE;
    }

    if (notificationFrames == 0) {
        notificationFrames = frameCount/2;
    }

    mSessionId = sessionId;

    // create the IAudioRecord
    status = openRecord(sampleRate, format, channelCount,
                        frameCount, flags, input);
    if (status != NO_ERROR) {
        return status;
    }

    if (cbf != 0) {
        mClientRecordThread = new ClientRecordThread(*this, threadCanCallJava);
        if (mClientRecordThread == 0) {
            return NO_INIT;
        }
    }

    mStatus = NO_ERROR;

    mFormat = format;
    // Update buffer size in case it has been limited by AudioFlinger during track creation
    mFrameCount = mCblk->frameCount;
    mChannelCount = (uint8_t)channelCount;
    mChannels = channels;
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
    mFlags = flags;
    mInput = input;

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
    if (AudioSystem::isLinearPCM(mFormat)) {
        return channelCount()*((format() == AudioSystem::PCM_8_BIT) ? sizeof(uint8_t) : sizeof(int16_t));
    } else {
        return sizeof(uint8_t);
    }
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
        ret = mAudioRecord->start();
        if (ret == DEAD_OBJECT) {
            LOGV("start() dead IAudioRecord: creating a new one");
            ret = openRecord(mCblk->sampleRate, mFormat, mChannelCount,
                    mFrameCount, mFlags, getInput());
            if (ret == NO_ERROR) {
                ret = mAudioRecord->start();
            }
        }
        if (ret == NO_ERROR) {
            mNewPosition = mCblk->user + mUpdatePeriod;
            mCblk->bufferTimeoutMs = MAX_RUN_TIMEOUT_MS;
            mCblk->waitTimeMs = 0;
            if (t != 0) {
               t->run("ClientRecordThread", THREAD_PRIORITY_AUDIO_CLIENT);
            } else {
                setpriority(PRIO_PROCESS, 0, THREAD_PRIORITY_AUDIO_CLIENT);
            }
        } else {
            LOGV("start() failed");
            android_atomic_and(~1, &mActive);
        }
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

unsigned int AudioRecord::getInputFramesLost()
{
    if (mActive)
        return AudioSystem::getInputFramesLost(mInput);
    else
        return 0;
}

// -------------------------------------------------------------------------

status_t AudioRecord::openRecord(
        uint32_t sampleRate,
        int format,
        int channelCount,
        int frameCount,
        uint32_t flags,
        audio_io_handle_t input)
{
    status_t status;
    const sp<IAudioFlinger>& audioFlinger = AudioSystem::get_audio_flinger();
    if (audioFlinger == 0) {
        return NO_INIT;
    }

    sp<IAudioRecord> record = audioFlinger->openRecord(getpid(), input,
                                                       sampleRate, format,
                                                       channelCount,
                                                       frameCount,
                                                       ((uint16_t)flags) << 16,
                                                       &mSessionId,
                                                       &status);
    if (record == 0) {
        LOGE("AudioFlinger could not create record track, status: %d", status);
        return status;
    }
    sp<IMemory> cblk = record->getCblk();
    if (cblk == 0) {
        LOGE("Could not get control block");
        return NO_INIT;
    }
    mAudioRecord.clear();
    mAudioRecord = record;
    mCblkMemory.clear();
    mCblkMemory = cblk;
    mCblk = static_cast<audio_track_cblk_t*>(cblk->pointer());
    mCblk->buffers = (char*)mCblk + sizeof(audio_track_cblk_t);
    mCblk->flags &= ~CBLK_DIRECTION_MSK;
    mCblk->bufferTimeoutMs = MAX_RUN_TIMEOUT_MS;
    mCblk->waitTimeMs = 0;
    return NO_ERROR;
}

status_t AudioRecord::obtainBuffer(Buffer* audioBuffer, int32_t waitCount)
{
    int active;
    status_t result;
    audio_track_cblk_t* cblk = mCblk;
    uint32_t framesReq = audioBuffer->frameCount;
    uint32_t waitTimeMs = (waitCount < 0) ? cblk->bufferTimeoutMs : WAIT_PERIOD_MS;

    audioBuffer->frameCount  = 0;
    audioBuffer->size        = 0;

    uint32_t framesReady = cblk->framesReady();

    if (framesReady == 0) {
        cblk->lock.lock();
        goto start_loop_here;
        while (framesReady == 0) {
            active = mActive;
            if (UNLIKELY(!active)) {
                cblk->lock.unlock();
                return NO_MORE_BUFFERS;
            }
            if (UNLIKELY(!waitCount)) {
                cblk->lock.unlock();
                return WOULD_BLOCK;
            }
            result = cblk->cv.waitRelative(cblk->lock, milliseconds(waitTimeMs));
            if (__builtin_expect(result!=NO_ERROR, false)) {
                cblk->waitTimeMs += waitTimeMs;
                if (cblk->waitTimeMs >= cblk->bufferTimeoutMs) {
                    LOGW(   "obtainBuffer timed out (is the CPU pegged?) "
                            "user=%08x, server=%08x", cblk->user, cblk->server);
                    cblk->lock.unlock();
                    result = mAudioRecord->start();
                    if (result == DEAD_OBJECT) {
                        LOGW("obtainBuffer() dead IAudioRecord: creating a new one");
                        result = openRecord(cblk->sampleRate, mFormat, mChannelCount,
                                            mFrameCount, mFlags, getInput());
                        if (result == NO_ERROR) {
                            cblk = mCblk;
                            mAudioRecord->start();
                        }
                    }
                    cblk->lock.lock();
                    cblk->waitTimeMs = 0;
                }
                if (--waitCount == 0) {
                    cblk->lock.unlock();
                    return TIMED_OUT;
                }
            }
            // read the server count again
        start_loop_here:
            framesReady = cblk->framesReady();
        }
        cblk->lock.unlock();
    }

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
    audioBuffer->size        = framesReq*cblk->frameSize;
    audioBuffer->raw         = (int8_t*)cblk->buffer(u);
    active = mActive;
    return active ? status_t(NO_ERROR) : status_t(STOPPED);
}

void AudioRecord::releaseBuffer(Buffer* audioBuffer)
{
    audio_track_cblk_t* cblk = mCblk;
    cblk->stepUser(audioBuffer->frameCount);
}

audio_io_handle_t AudioRecord::getInput()
{
    mInput = AudioSystem::getInput(mInputSource,
                                mCblk->sampleRate,
                                mFormat, mChannels,
                                (AudioSystem::audio_in_acoustics)mFlags);
    return mInput;
}

int AudioRecord::getSessionId()
{
    return mSessionId;
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


    do {

        audioBuffer.frameCount = userSize/frameSize();

        // By using a wait count corresponding to twice the timeout period in
        // obtainBuffer() we give a chance to recover once for a read timeout
        // (if media_server crashed for instance) before returning a length of
        // 0 bytes read to the client
        status_t err = obtainBuffer(&audioBuffer, ((2 * MAX_RUN_TIMEOUT_MS) / WAIT_PERIOD_MS));
        if (err < 0) {
            // out of buffers, return #bytes written
            if (err == status_t(NO_MORE_BUFFERS))
                break;
            if (err == status_t(TIMED_OUT))
                err = 0;
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
        audioBuffer.frameCount = readSize/frameSize();
        frames -= audioBuffer.frameCount;

        releaseBuffer(&audioBuffer);

    } while (frames);


    // Manage overrun callback
    if (mActive && (mCblk->framesAvailable_l() == 0)) {
        LOGV("Overrun user: %x, server: %x, flags %04x", mCblk->user, mCblk->server, mCblk->flags);
        if ((mCblk->flags & CBLK_UNDERRUN_MSK) == CBLK_UNDERRUN_OFF) {
            mCbf(EVENT_OVERRUN, mUserData, 0);
            mCblk->flags |= CBLK_UNDERRUN_ON;
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

