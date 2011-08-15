/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "LibAAH_RTP"
//#define LOG_NDEBUG 0

#include <aah_timesrv/cc_helper.h>
#include <binder/IServiceManager.h>
#include <media/MediaPlayerInterface.h>
#include <utils/Log.h>

#include "aah_rx_player.h"

namespace android {

const uint32_t AAH_RXPlayer::kRTPRingBufferSize = 1 << 10;

sp<MediaPlayerBase> createAAH_RXPlayer() {
    sp<MediaPlayerBase> ret = new AAH_RXPlayer();
    return ret;
}

AAH_RXPlayer::AAH_RXPlayer()
        : ring_buffer_(kRTPRingBufferSize)
        , substreams_(NULL) {
    thread_wrapper_ = new ThreadWrapper(*this);

    is_playing_          = false;
    multicast_joined_    = false;
    transmitter_known_   = false;
    current_epoch_known_ = false;
    data_source_set_     = false;
    sock_fd_             = -1;

    substreams_.setCapacity(4);

    memset(&listen_addr_,      0, sizeof(listen_addr_));
    memset(&transmitter_addr_, 0, sizeof(transmitter_addr_));

    fetchAudioFlinger();
}

AAH_RXPlayer::~AAH_RXPlayer() {
    reset_l();
    CHECK(substreams_.size() == 0);
    omx_.disconnect();
}

status_t AAH_RXPlayer::initCheck() {
    if (thread_wrapper_ == NULL) {
        LOGE("Failed to allocate thread wrapper!");
        return NO_MEMORY;
    }

    if (!ring_buffer_.initCheck()) {
        LOGE("Failed to allocate reassembly ring buffer!");
        return NO_MEMORY;
    }

    // Check for the presense of the A@H common time service by attempting to
    // query for CommonTime's frequency.  If we get an error back, we cannot
    // talk to the service at all and should abort now.
    status_t res;
    uint64_t freq;
    res = CCHelper::getCommonFreq(&freq);
    if (OK != res) {
        LOGE("Failed to connect to common time service!");
        return res;
    }

    return omx_.connect();
}

status_t AAH_RXPlayer::setDataSource(
        const char *url,
        const KeyedVector<String8, String8> *headers) {
    AutoMutex api_lock(&api_lock_);
    uint32_t a, b, c, d;
    uint16_t port;

    if (data_source_set_) {
        return INVALID_OPERATION;
    }

    if (NULL == url) {
        return BAD_VALUE;
    }

    if (5 != sscanf(url, "%*[^:/]://%u.%u.%u.%u:%hu", &a, &b, &c, &d, &port)) {
        LOGE("Failed to parse URL \"%s\"", url);
        return BAD_VALUE;
    }

    if ((a > 255) || (b > 255) || (c > 255) || (d > 255) || (port == 0)) {
        LOGE("Bad multicast address \"%s\"", url);
        return BAD_VALUE;
    }

    LOGI("setDataSource :: %u.%u.%u.%u:%hu", a, b, c, d, port);

    a = (a << 24) | (b << 16) | (c <<  8) | d;

    memset(&listen_addr_, 0, sizeof(listen_addr_));
    listen_addr_.sin_family      = AF_INET;
    listen_addr_.sin_port        = htons(port);
    listen_addr_.sin_addr.s_addr = htonl(a);
    data_source_set_ = true;

    return OK;
}

status_t AAH_RXPlayer::setDataSource(int fd, int64_t offset, int64_t length) {
    return INVALID_OPERATION;
}

status_t AAH_RXPlayer::setVideoSurface(const sp<Surface>& surface) {
    return OK;
}

status_t AAH_RXPlayer::setVideoSurfaceTexture(
        const sp<ISurfaceTexture>& surfaceTexture) {
    return OK;
}

status_t AAH_RXPlayer::prepare() {
    return OK;
}

status_t AAH_RXPlayer::prepareAsync() {
    sendEvent(MEDIA_PREPARED);
    return OK;
}

status_t AAH_RXPlayer::start() {
    AutoMutex api_lock(&api_lock_);

    if (is_playing_) {
        return OK;
    }

    status_t res = startWorkThread();
    is_playing_ = (res == OK);
    return res;
}

status_t AAH_RXPlayer::stop() {
    return pause();
}

status_t AAH_RXPlayer::pause() {
    AutoMutex api_lock(&api_lock_);
    stopWorkThread();
    CHECK(sock_fd_ < 0);
    is_playing_ = false;
    return OK;
}

bool AAH_RXPlayer::isPlaying() {
    AutoMutex api_lock(&api_lock_);
    return is_playing_;
}

status_t AAH_RXPlayer::seekTo(int msec) {
    sendEvent(MEDIA_SEEK_COMPLETE);
    return OK;
}

status_t AAH_RXPlayer::getCurrentPosition(int *msec) {
    if (NULL != msec) {
        *msec = 0;
    }
    return OK;
}

status_t AAH_RXPlayer::getDuration(int *msec) {
    if (NULL != msec) {
        *msec = 1;
    }
    return OK;
}

status_t AAH_RXPlayer::reset() {
    AutoMutex api_lock(&api_lock_);
    reset_l();
    return OK;
}

void AAH_RXPlayer::reset_l() {
    stopWorkThread();
    CHECK(sock_fd_ < 0);
    CHECK(!multicast_joined_);
    is_playing_ = false;
    data_source_set_ = false;
    transmitter_known_ = false;
    memset(&listen_addr_, 0, sizeof(listen_addr_));
}

status_t AAH_RXPlayer::setLooping(int loop) {
    return OK;
}

player_type AAH_RXPlayer::playerType() {
    return AAH_RX_PLAYER;
}

status_t AAH_RXPlayer::setParameter(int key, const Parcel &request) {
    return ERROR_UNSUPPORTED;
}

status_t AAH_RXPlayer::getParameter(int key, Parcel *reply) {
    return ERROR_UNSUPPORTED;
}

status_t AAH_RXPlayer::invoke(const Parcel& request, Parcel *reply) {
    if (!reply) {
        return BAD_VALUE;
    }

    int32_t magic;
    status_t err = request.readInt32(&magic);
    if (err != OK) {
        reply->writeInt32(err);
        return OK;
    }

    if (magic != 0x12345) {
        reply->writeInt32(BAD_VALUE);
        return OK;
    }

    int32_t methodID;
    err = request.readInt32(&methodID);
    if (err != OK) {
        reply->writeInt32(err);
        return OK;
    }

    switch (methodID) {
        // Get Volume
        case INVOKE_GET_MASTER_VOLUME: {
            if (audio_flinger_ != NULL) {
                reply->writeInt32(OK);
                reply->writeFloat(audio_flinger_->masterVolume());
            } else {
                reply->writeInt32(UNKNOWN_ERROR);
            }
        } break;

        // Set Volume
        case INVOKE_SET_MASTER_VOLUME: {
            float targetVol = request.readFloat();
            reply->writeInt32(audio_flinger_->setMasterVolume(targetVol));
        } break;

        default: return BAD_VALUE;
    }

    return OK;
}

void AAH_RXPlayer::fetchAudioFlinger() {
    if (audio_flinger_ == NULL) {
        sp<IServiceManager> sm = defaultServiceManager();
        sp<IBinder> binder;
        binder = sm->getService(String16("media.audio_flinger"));

        if (binder == NULL) {
            LOGW("AAH_RXPlayer failed to fetch handle to audio flinger."
                 " Master volume control will not be possible.");
        }

        audio_flinger_ = interface_cast<IAudioFlinger>(binder);
    }
}

}  // namespace android
