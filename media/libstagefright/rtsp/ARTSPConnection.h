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

#ifndef A_RTSP_CONNECTION_H_

#define A_RTSP_CONNECTION_H_

#include <media/stagefright/foundation/AHandler.h>
#include <media/stagefright/foundation/AString.h>

namespace android {

struct ABuffer;

struct ARTSPResponse : public RefBase {
    unsigned long mStatusCode;
    AString mStatusLine;
    KeyedVector<AString,AString> mHeaders;
    sp<ABuffer> mContent;
};

struct ARTSPConnection : public AHandler {
    ARTSPConnection();

    void connect(const char *url, const sp<AMessage> &reply);
    void disconnect(const sp<AMessage> &reply);

    void sendRequest(const char *request, const sp<AMessage> &reply);

protected:
    virtual ~ARTSPConnection();
    virtual void onMessageReceived(const sp<AMessage> &msg);

private:
    enum State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    };

    enum {
        kWhatConnect            = 'conn',
        kWhatDisconnect         = 'disc',
        kWhatCompleteConnection = 'comc',
        kWhatSendRequest        = 'sreq',
        kWhatReceiveResponse    = 'rres',
    };

    static const int64_t kSelectTimeoutUs;

    State mState;
    int mSocket;
    int32_t mConnectionID;
    int32_t mNextCSeq;
    bool mReceiveResponseEventPending;

    KeyedVector<int32_t, sp<AMessage> > mPendingRequests;

    void onConnect(const sp<AMessage> &msg);
    void onDisconnect(const sp<AMessage> &msg);
    void onCompleteConnection(const sp<AMessage> &msg);
    void onSendRequest(const sp<AMessage> &msg);
    void onReceiveResponse();

    void flushPendingRequests();
    void postReceiveReponseEvent();

    // Return false iff something went unrecoverably wrong.
    bool receiveRTSPReponse();
    bool receiveLine(AString *line);
    bool notifyResponseListener(const sp<ARTSPResponse> &response);

    static bool ParseURL(
            const char *url, AString *host, unsigned *port, AString *path);

    static bool ParseSingleUnsignedLong(
            const char *from, unsigned long *x);

    DISALLOW_EVIL_CONSTRUCTORS(ARTSPConnection);
};

}  // namespace android

#endif  // A_RTSP_CONNECTION_H_
