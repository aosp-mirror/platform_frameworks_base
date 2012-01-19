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
#define LOG_TAG "ARTPSession"
#include <utils/Log.h>

#include "ARTPSession.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/hexdump.h>

#include <ctype.h>
#include <arpa/inet.h>
#include <sys/socket.h>

#include "APacketSource.h"
#include "ARTPConnection.h"
#include "ASessionDescription.h"

namespace android {

ARTPSession::ARTPSession()
    : mInitCheck(NO_INIT) {
}

status_t ARTPSession::setup(const sp<ASessionDescription> &desc) {
    CHECK_EQ(mInitCheck, (status_t)NO_INIT);

    mDesc = desc;

    mRTPConn = new ARTPConnection(ARTPConnection::kRegularlyRequestFIR);

    looper()->registerHandler(mRTPConn);

    for (size_t i = 1; i < mDesc->countTracks(); ++i) {
        AString connection;
        if (!mDesc->findAttribute(i, "c=", &connection)) {
            // No per-stream connection information, try global fallback.
            if (!mDesc->findAttribute(0, "c=", &connection)) {
                LOGE("Unable to find connection attribute.");
                return mInitCheck;
            }
        }
        if (!(connection == "IN IP4 127.0.0.1")) {
            LOGE("We only support localhost connections for now.");
            return mInitCheck;
        }

        unsigned port;
        if (!validateMediaFormat(i, &port) || (port & 1) != 0) {
            LOGE("Invalid media format.");
            return mInitCheck;
        }

        sp<APacketSource> source = new APacketSource(mDesc, i);
        if (source->initCheck() != OK) {
            LOGE("Unsupported format.");
            return mInitCheck;
        }

        int rtpSocket = MakeUDPSocket(port);
        int rtcpSocket = MakeUDPSocket(port + 1);

        mTracks.push(TrackInfo());
        TrackInfo *info = &mTracks.editItemAt(mTracks.size() - 1);
        info->mRTPSocket = rtpSocket;
        info->mRTCPSocket = rtcpSocket;

        sp<AMessage> notify = new AMessage(kWhatAccessUnitComplete, id());
        notify->setSize("track-index", mTracks.size() - 1);

        mRTPConn->addStream(
                rtpSocket, rtcpSocket, mDesc, i, notify, false /* injected */);

        info->mPacketSource = source;
    }

    mInitCheck = OK;

    return OK;
}

// static
int ARTPSession::MakeUDPSocket(unsigned port) {
    int s = socket(AF_INET, SOCK_DGRAM, 0);
    CHECK_GE(s, 0);

    struct sockaddr_in addr;
    memset(addr.sin_zero, 0, sizeof(addr.sin_zero));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(port);

    CHECK_EQ(0, bind(s, (const struct sockaddr *)&addr, sizeof(addr)));

    return s;
}

ARTPSession::~ARTPSession() {
    for (size_t i = 0; i < mTracks.size(); ++i) {
        TrackInfo *info = &mTracks.editItemAt(i);

        info->mPacketSource->signalEOS(UNKNOWN_ERROR);

        close(info->mRTPSocket);
        close(info->mRTCPSocket);
    }
}

void ARTPSession::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatAccessUnitComplete:
        {
            int32_t firstRTCP;
            if (msg->findInt32("first-rtcp", &firstRTCP)) {
                // There won't be an access unit here, it's just a notification
                // that the data communication worked since we got the first
                // rtcp packet.
                break;
            }

            size_t trackIndex;
            CHECK(msg->findSize("track-index", &trackIndex));

            int32_t eos;
            if (msg->findInt32("eos", &eos) && eos) {
                TrackInfo *info = &mTracks.editItemAt(trackIndex);
                info->mPacketSource->signalEOS(ERROR_END_OF_STREAM);
                break;
            }

            sp<RefBase> obj;
            CHECK(msg->findObject("access-unit", &obj));

            sp<ABuffer> accessUnit = static_cast<ABuffer *>(obj.get());

            uint64_t ntpTime;
            CHECK(accessUnit->meta()->findInt64(
                        "ntp-time", (int64_t *)&ntpTime));

#if 0
#if 0
            printf("access unit complete size=%d\tntp-time=0x%016llx\n",
                   accessUnit->size(), ntpTime);
#else
            ALOGI("access unit complete, size=%d, ntp-time=%llu",
                 accessUnit->size(), ntpTime);
            hexdump(accessUnit->data(), accessUnit->size());
#endif
#endif

#if 0
            CHECK_GE(accessUnit->size(), 5u);
            CHECK(!memcmp("\x00\x00\x00\x01", accessUnit->data(), 4));
            unsigned x = accessUnit->data()[4];

            ALOGI("access unit complete: nalType=0x%02x, nalRefIdc=0x%02x",
                 x & 0x1f, (x & 0x60) >> 5);
#endif

            accessUnit->meta()->setInt64("ntp-time", ntpTime);
            accessUnit->meta()->setInt64("timeUs", 0);

#if 0
            int32_t damaged;
            if (accessUnit->meta()->findInt32("damaged", &damaged)
                    && damaged != 0) {
                ALOGI("ignoring damaged AU");
            } else
#endif
            {
                TrackInfo *info = &mTracks.editItemAt(trackIndex);
                info->mPacketSource->queueAccessUnit(accessUnit);
            }
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

bool ARTPSession::validateMediaFormat(size_t index, unsigned *port) const {
    AString format;
    mDesc->getFormat(index, &format);

    ssize_t i = format.find(" ");
    if (i < 0) {
        return false;
    }

    ++i;
    size_t j = i;
    while (isdigit(format.c_str()[j])) {
        ++j;
    }
    if (format.c_str()[j] != ' ') {
        return false;
    }

    AString portString(format, i, j - i);

    char *end;
    unsigned long x = strtoul(portString.c_str(), &end, 10);
    if (end == portString.c_str() || *end != '\0') {
        return false;
    }

    if (x == 0 || x > 65535) {
        return false;
    }

    *port = x;

    return true;
}

size_t ARTPSession::countTracks() {
    return mTracks.size();
}

sp<MediaSource> ARTPSession::trackAt(size_t index) {
    CHECK_LT(index, mTracks.size());
    return mTracks.editItemAt(index).mPacketSource;
}

}  // namespace android
