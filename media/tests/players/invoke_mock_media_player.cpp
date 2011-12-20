/*
 * Copyright (C) 2009 The Android Open Source Project
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
#define LOG_TAG "TestPlayerStub"
#include "utils/Log.h"

#include <string.h>

#include <binder/Parcel.h>
#include <media/MediaPlayerInterface.h>
#include <utils/Errors.h>

using android::INVALID_OPERATION;
using android::Surface;
using android::ISurfaceTexture;
using android::MediaPlayerBase;
using android::OK;
using android::Parcel;
using android::SortedVector;
using android::TEST_PLAYER;
using android::UNKNOWN_ERROR;
using android::player_type;
using android::sp;
using android::status_t;
using android::String8;
using android::KeyedVector;

// This file contains a test player that is loaded via the
// TestPlayerStub class.  The player contains various implementation
// of the invoke method that java tests can use.

namespace {
const char *kPing = "ping";

class Player: public MediaPlayerBase
{
  public:
    enum TestType {TEST_UNKNOWN, PING};
    Player() {}
    virtual ~Player() {}

    virtual status_t    initCheck() {return OK;}
    virtual bool        hardwareOutput() {return true;}

    virtual status_t    setDataSource(
            const char *url,
            const KeyedVector<String8, String8> *) {
        ALOGV("setDataSource %s", url);
        mTest = TEST_UNKNOWN;
        if (strncmp(url, kPing, strlen(kPing)) == 0) {
            mTest = PING;
        }
        return OK;
    }

    virtual status_t    setDataSource(int fd, int64_t offset, int64_t length) {return OK;}
    virtual status_t    setVideoSurfaceTexture(
                                const sp<ISurfaceTexture>& surfaceTexture) {return OK;}
    virtual status_t    prepare() {return OK;}
    virtual status_t    prepareAsync() {return OK;}
    virtual status_t    start() {return OK;}
    virtual status_t    stop() {return OK;}
    virtual status_t    pause() {return OK;}
    virtual bool        isPlaying() {return true;}
    virtual status_t    seekTo(int msec) {return OK;}
    virtual status_t    getCurrentPosition(int *msec) {return OK;}
    virtual status_t    getDuration(int *msec) {return OK;}
    virtual status_t    reset() {return OK;}
    virtual status_t    setLooping(int loop) {return OK;}
    virtual player_type playerType() {return TEST_PLAYER;}
    virtual status_t    invoke(const Parcel& request, Parcel *reply);
    virtual status_t    setParameter(int key, const Parcel &request) {return OK;}
    virtual status_t    getParameter(int key, Parcel *reply) {return OK;}


  private:
    // Take a request, copy it to the reply.
    void ping(const Parcel& request, Parcel *reply);

    status_t mStatus;
    TestType mTest;
};

status_t Player::invoke(const Parcel& request, Parcel *reply)
{
    switch (mTest) {
        case PING:
            ping(request, reply);
            break;
        default: mStatus = UNKNOWN_ERROR;
    }
    return mStatus;
}

void Player::ping(const Parcel& request, Parcel *reply)
{
    const size_t len = request.dataAvail();

    reply->setData(static_cast<const uint8_t*>(request.readInplace(len)), len);
    mStatus = OK;
}

}

extern "C" android::MediaPlayerBase* newPlayer()
{
    ALOGD("New invoke test player");
    return new Player();
}

extern "C" android::status_t deletePlayer(android::MediaPlayerBase *player)
{
    ALOGD("Delete invoke test player");
    delete player;
    return OK;
}
