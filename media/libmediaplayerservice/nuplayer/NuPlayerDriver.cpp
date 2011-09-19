/*
 * Copyright (C) 2010 The Android Open Source Project
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
#define LOG_TAG "NuPlayerDriver"
#include <utils/Log.h>

#include "NuPlayerDriver.h"

#include "NuPlayer.h"

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>

namespace android {

NuPlayerDriver::NuPlayerDriver()
    : mResetInProgress(false),
      mDurationUs(-1),
      mPositionUs(-1),
      mNumFramesTotal(0),
      mNumFramesDropped(0),
      mLooper(new ALooper),
      mState(UNINITIALIZED),
      mStartupSeekTimeUs(-1) {
    mLooper->setName("NuPlayerDriver Looper");

    mLooper->start(
            false, /* runOnCallingThread */
            true,  /* canCallJava */
            PRIORITY_AUDIO);

    mPlayer = new NuPlayer;
    mLooper->registerHandler(mPlayer);

    mPlayer->setDriver(this);
}

NuPlayerDriver::~NuPlayerDriver() {
    mLooper->stop();
}

status_t NuPlayerDriver::initCheck() {
    return OK;
}

status_t NuPlayerDriver::setUID(uid_t uid) {
    mPlayer->setUID(uid);

    return OK;
}

status_t NuPlayerDriver::setDataSource(
        const char *url, const KeyedVector<String8, String8> *headers) {
    CHECK_EQ((int)mState, (int)UNINITIALIZED);

    mPlayer->setDataSource(url, headers);

    mState = STOPPED;

    return OK;
}

status_t NuPlayerDriver::setDataSource(int fd, int64_t offset, int64_t length) {
    return INVALID_OPERATION;
}

status_t NuPlayerDriver::setDataSource(const sp<IStreamSource> &source) {
    CHECK_EQ((int)mState, (int)UNINITIALIZED);

    mPlayer->setDataSource(source);

    mState = STOPPED;

    return OK;
}

status_t NuPlayerDriver::setVideoSurface(const sp<Surface> &surface) {
    mPlayer->setVideoSurface(surface);

    return OK;
}

status_t NuPlayerDriver::setVideoSurfaceTexture(
        const sp<ISurfaceTexture> &surfaceTexture) {
    mPlayer->setVideoSurfaceTexture(surfaceTexture);

    return OK;
}

status_t NuPlayerDriver::prepare() {
    return OK;
}

status_t NuPlayerDriver::prepareAsync() {
    sendEvent(MEDIA_PREPARED);

    return OK;
}

status_t NuPlayerDriver::start() {
    switch (mState) {
        case UNINITIALIZED:
            return INVALID_OPERATION;
        case STOPPED:
        {
            mPlayer->start();

            if (mStartupSeekTimeUs >= 0) {
                if (mStartupSeekTimeUs == 0) {
                    notifySeekComplete();
                } else {
                    mPlayer->seekToAsync(mStartupSeekTimeUs);
                }

                mStartupSeekTimeUs = -1;
            }

            break;
        }
        case PLAYING:
            return OK;
        default:
        {
            CHECK_EQ((int)mState, (int)PAUSED);

            mPlayer->resume();
            break;
        }
    }

    mState = PLAYING;

    return OK;
}

status_t NuPlayerDriver::stop() {
    return pause();
}

status_t NuPlayerDriver::pause() {
    switch (mState) {
        case UNINITIALIZED:
            return INVALID_OPERATION;
        case STOPPED:
            return OK;
        case PLAYING:
            mPlayer->pause();
            break;
        default:
        {
            CHECK_EQ((int)mState, (int)PAUSED);
            return OK;
        }
    }

    mState = PAUSED;

    return OK;
}

bool NuPlayerDriver::isPlaying() {
    return mState == PLAYING;
}

status_t NuPlayerDriver::seekTo(int msec) {
    int64_t seekTimeUs = msec * 1000ll;

    switch (mState) {
        case UNINITIALIZED:
            return INVALID_OPERATION;
        case STOPPED:
        {
            mStartupSeekTimeUs = seekTimeUs;
            break;
        }
        case PLAYING:
        case PAUSED:
        {
            mPlayer->seekToAsync(seekTimeUs);
            break;
        }

        default:
            TRESPASS();
            break;
    }

    return OK;
}

status_t NuPlayerDriver::getCurrentPosition(int *msec) {
    Mutex::Autolock autoLock(mLock);

    if (mPositionUs < 0) {
        *msec = 0;
    } else {
        *msec = (mPositionUs + 500ll) / 1000;
    }

    return OK;
}

status_t NuPlayerDriver::getDuration(int *msec) {
    Mutex::Autolock autoLock(mLock);

    if (mDurationUs < 0) {
        *msec = 0;
    } else {
        *msec = (mDurationUs + 500ll) / 1000;
    }

    return OK;
}

status_t NuPlayerDriver::reset() {
    Mutex::Autolock autoLock(mLock);
    mResetInProgress = true;

    mPlayer->resetAsync();

    while (mResetInProgress) {
        mCondition.wait(mLock);
    }

    mDurationUs = -1;
    mPositionUs = -1;
    mState = UNINITIALIZED;
    mStartupSeekTimeUs = -1;

    return OK;
}

status_t NuPlayerDriver::setLooping(int loop) {
    return INVALID_OPERATION;
}

player_type NuPlayerDriver::playerType() {
    return NU_PLAYER;
}

status_t NuPlayerDriver::invoke(const Parcel &request, Parcel *reply) {
    return INVALID_OPERATION;
}

void NuPlayerDriver::setAudioSink(const sp<AudioSink> &audioSink) {
    mPlayer->setAudioSink(audioSink);
}

status_t NuPlayerDriver::setParameter(int key, const Parcel &request) {
    return INVALID_OPERATION;
}

status_t NuPlayerDriver::getParameter(int key, Parcel *reply) {
    return INVALID_OPERATION;
}

status_t NuPlayerDriver::getMetadata(
        const media::Metadata::Filter& ids, Parcel *records) {
    return INVALID_OPERATION;
}

void NuPlayerDriver::notifyResetComplete() {
    Mutex::Autolock autoLock(mLock);
    CHECK(mResetInProgress);
    mResetInProgress = false;
    mCondition.broadcast();
}

void NuPlayerDriver::notifyDuration(int64_t durationUs) {
    Mutex::Autolock autoLock(mLock);
    mDurationUs = durationUs;
}

void NuPlayerDriver::notifyPosition(int64_t positionUs) {
    Mutex::Autolock autoLock(mLock);
    mPositionUs = positionUs;
}

void NuPlayerDriver::notifySeekComplete() {
    sendEvent(MEDIA_SEEK_COMPLETE);
}

void NuPlayerDriver::notifyFrameStats(
        int64_t numFramesTotal, int64_t numFramesDropped) {
    Mutex::Autolock autoLock(mLock);
    mNumFramesTotal = numFramesTotal;
    mNumFramesDropped = numFramesDropped;
}

status_t NuPlayerDriver::dump(int fd, const Vector<String16> &args) const {
    Mutex::Autolock autoLock(mLock);

    FILE *out = fdopen(dup(fd), "w");

    fprintf(out, " NuPlayer\n");
    fprintf(out, "  numFramesTotal(%lld), numFramesDropped(%lld), "
                 "percentageDropped(%.2f)\n",
                 mNumFramesTotal,
                 mNumFramesDropped,
                 mNumFramesTotal == 0
                    ? 0.0 : (double)mNumFramesDropped / mNumFramesTotal);

    fclose(out);
    out = NULL;

    return OK;
}

}  // namespace android
