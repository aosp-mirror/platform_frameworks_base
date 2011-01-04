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
      mLooper(new ALooper),
      mPlayer(false) {
    mLooper->setName("NuPlayerDriver Looper");

    mLooper->start(
            false, /* runOnCallingThread */
            true,  /* canCallJava */
            PRIORITY_AUDIO);

    mPlayer = new NuPlayer;
    mLooper->registerHandler(mPlayer);

    mPlayer->setListener(this);
}

NuPlayerDriver::~NuPlayerDriver() {
    mLooper->stop();
}

status_t NuPlayerDriver::initCheck() {
    return OK;
}

status_t NuPlayerDriver::setDataSource(
        const char *url, const KeyedVector<String8, String8> *headers) {
    mPlayer->setDataSource(url, headers);

    return OK;
}

status_t NuPlayerDriver::setDataSource(int fd, int64_t offset, int64_t length) {
    return INVALID_OPERATION;
}

status_t NuPlayerDriver::setDataSource(const sp<IStreamSource> &source) {
    mPlayer->setDataSource(source);

    return OK;
}

status_t NuPlayerDriver::setVideoSurface(const sp<Surface> &surface) {
    mPlayer->setVideoSurface(surface);

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
    mPlayer->start();
    mPlaying = true;

    return OK;
}

status_t NuPlayerDriver::stop() {
    mPlaying = false;
    return OK;
}

status_t NuPlayerDriver::pause() {
    mPlaying = false;
    return OK;
}

bool NuPlayerDriver::isPlaying() {
    return mPlaying;
}

status_t NuPlayerDriver::seekTo(int msec) {
    return INVALID_OPERATION;
}

status_t NuPlayerDriver::getCurrentPosition(int *msec) {
    *msec = 0;

    return OK;
}

status_t NuPlayerDriver::getDuration(int *msec) {
    *msec = 0;

    return OK;
}

status_t NuPlayerDriver::reset() {
    Mutex::Autolock autoLock(mLock);
    mResetInProgress = true;

    mPlayer->resetAsync();

    while (mResetInProgress) {
        mCondition.wait(mLock);
    }

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

status_t NuPlayerDriver::getMetadata(
        const media::Metadata::Filter& ids, Parcel *records) {
    return INVALID_OPERATION;
}

void NuPlayerDriver::sendEvent(int msg, int ext1, int ext2) {
    if (msg != MEDIA_RESET_COMPLETE) {
        MediaPlayerInterface::sendEvent(msg, ext1, ext2);
        return;
    }

    Mutex::Autolock autoLock(mLock);
    CHECK(mResetInProgress);
    mResetInProgress = false;
    mCondition.broadcast();
}

}  // namespace android
