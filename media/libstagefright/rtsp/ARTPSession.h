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

#ifndef A_RTP_SESSION_H_

#define A_RTP_SESSION_H_

#include <media/stagefright/foundation/AHandler.h>

namespace android {

struct APacketSource;
struct ARTPConnection;
struct ASessionDescription;
struct MediaSource;

struct ARTPSession : public AHandler {
    ARTPSession();

    status_t setup(const sp<ASessionDescription> &desc);

    size_t countTracks();
    sp<MediaSource> trackAt(size_t index);

protected:
    virtual void onMessageReceived(const sp<AMessage> &msg);

    virtual ~ARTPSession();

private:
    enum {
        kWhatAccessUnitComplete = 'accu'
    };

    struct TrackInfo {
        int mRTPSocket;
        int mRTCPSocket;

        sp<APacketSource> mPacketSource;
    };

    status_t mInitCheck;
    sp<ASessionDescription> mDesc;
    sp<ARTPConnection> mRTPConn;

    Vector<TrackInfo> mTracks;

    bool validateMediaFormat(size_t index, unsigned *port) const;
    static int MakeUDPSocket(unsigned port);

    DISALLOW_EVIL_CONSTRUCTORS(ARTPSession);
};

}  // namespace android

#endif  // A_RTP_SESSION_H_
