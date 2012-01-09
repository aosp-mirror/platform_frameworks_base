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
#define LOG_TAG "UDPPusher"
#include <utils/Log.h>

#include "UDPPusher.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <utils/ByteOrder.h>

#include <sys/socket.h>

namespace android {

UDPPusher::UDPPusher(const char *filename, unsigned port)
    : mFile(fopen(filename, "rb")),
      mFirstTimeMs(0),
      mFirstTimeUs(0) {
    CHECK(mFile != NULL);

    mSocket = socket(AF_INET, SOCK_DGRAM, 0);

    struct sockaddr_in addr;
    memset(addr.sin_zero, 0, sizeof(addr.sin_zero));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = 0;

    CHECK_EQ(0, bind(mSocket, (const struct sockaddr *)&addr, sizeof(addr)));

    memset(mRemoteAddr.sin_zero, 0, sizeof(mRemoteAddr.sin_zero));
    mRemoteAddr.sin_family = AF_INET;
    mRemoteAddr.sin_addr.s_addr = INADDR_ANY;
    mRemoteAddr.sin_port = htons(port);
}

UDPPusher::~UDPPusher() {
    close(mSocket);
    mSocket = -1;

    fclose(mFile);
    mFile = NULL;
}

void UDPPusher::start() {
    uint32_t timeMs;
    CHECK_EQ(fread(&timeMs, 1, sizeof(timeMs), mFile), sizeof(timeMs));
    mFirstTimeMs = fromlel(timeMs);
    mFirstTimeUs = ALooper::GetNowUs();

    (new AMessage(kWhatPush, id()))->post();
}

bool UDPPusher::onPush() {
    uint32_t length;
    if (fread(&length, 1, sizeof(length), mFile) < sizeof(length)) {
        ALOGI("No more data to push.");
        return false;
    }

    length = fromlel(length);

    CHECK_GT(length, 0u);

    sp<ABuffer> buffer = new ABuffer(length);
    if (fread(buffer->data(), 1, length, mFile) < length) {
        ALOGE("File truncated?.");
        return false;
    }

    ssize_t n = sendto(
            mSocket, buffer->data(), buffer->size(), 0,
            (const struct sockaddr *)&mRemoteAddr, sizeof(mRemoteAddr));

    CHECK_EQ(n, (ssize_t)buffer->size());

    uint32_t timeMs;
    if (fread(&timeMs, 1, sizeof(timeMs), mFile) < sizeof(timeMs)) {
        ALOGI("No more data to push.");
        return false;
    }

    timeMs = fromlel(timeMs);
    CHECK_GE(timeMs, mFirstTimeMs);

    timeMs -= mFirstTimeMs;
    int64_t whenUs = mFirstTimeUs + timeMs * 1000ll;
    int64_t nowUs = ALooper::GetNowUs();
    (new AMessage(kWhatPush, id()))->post(whenUs - nowUs);

    return true;
}

void UDPPusher::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatPush:
        {
            if (!onPush() && !(ntohs(mRemoteAddr.sin_port) & 1)) {
                ALOGI("emulating BYE packet");

                sp<ABuffer> buffer = new ABuffer(8);
                uint8_t *data = buffer->data();
                *data++ = (2 << 6) | 1;
                *data++ = 203;
                *data++ = 0;
                *data++ = 1;
                *data++ = 0x8f;
                *data++ = 0x49;
                *data++ = 0xc0;
                *data++ = 0xd0;
                buffer->setRange(0, 8);

                struct sockaddr_in tmp = mRemoteAddr;
                tmp.sin_port = htons(ntohs(mRemoteAddr.sin_port) | 1);

                ssize_t n = sendto(
                        mSocket, buffer->data(), buffer->size(), 0,
                        (const struct sockaddr *)&tmp,
                        sizeof(tmp));

                CHECK_EQ(n, (ssize_t)buffer->size());
            }
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

}  // namespace android

