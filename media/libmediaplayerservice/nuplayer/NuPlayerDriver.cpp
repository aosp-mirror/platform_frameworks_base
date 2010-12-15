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

#include <media/stagefright/foundation/ALooper.h>

namespace android {

NuPlayerDriver::NuPlayerDriver()
    : mLooper(new ALooper) {
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
    return INVALID_OPERATION;
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
    return OK;
}

status_t NuPlayerDriver::start() {
    mPlayer->start();

    return OK;
}

status_t NuPlayerDriver::stop() {
    return OK;
}

status_t NuPlayerDriver::pause() {
    return OK;
}

bool NuPlayerDriver::isPlaying() {
    return false;
}

status_t NuPlayerDriver::seekTo(int msec) {
    return INVALID_OPERATION;
}

status_t NuPlayerDriver::getCurrentPosition(int *msec) {
    return INVALID_OPERATION;
}

status_t NuPlayerDriver::getDuration(int *msec) {
    return INVALID_OPERATION;
}

status_t NuPlayerDriver::reset() {
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

}  // namespace android
