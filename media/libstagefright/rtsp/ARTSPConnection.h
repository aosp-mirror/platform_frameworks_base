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

    void observeBinaryData(const sp<AMessage> &reply);

    static bool ParseURL(
            const char *url, AString *host, unsigned *port, AString *path,
            AString *user, AString *pass);

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
        kWhatObserveBinaryData  = 'obin',
    };

    enum AuthType {
        NONE,
        BASIC,
        DIGEST
    };

    static const int64_t kSelectTimeoutUs;

    State mState;
    AString mUser, mPass;
    AuthType mAuthType;
    AString mNonce;
    int mSocket;
    int32_t mConnectionID;
    int32_t mNextCSeq;
    bool mReceiveResponseEventPending;

    KeyedVector<int32_t, sp<AMessage> > mPendingRequests;

    sp<AMessage> mObserveBinaryMessage;

    void onConnect(const sp<AMessage> &msg);
    void onDisconnect(const sp<AMessage> &msg);
    void onCompleteConnection(const sp<AMessage> &msg);
    void onSendRequest(const sp<AMessage> &msg);
    void onReceiveResponse();

    void flushPendingRequests();
    void postReceiveReponseEvent();

    // Return false iff something went unrecoverably wrong.
    bool receiveRTSPReponse();
    status_t receive(void *data, size_t size);
    bool receiveLine(AString *line);
    sp<ABuffer> receiveBinaryData();
    bool notifyResponseListener(const sp<ARTSPResponse> &response);

    bool parseAuthMethod(const sp<ARTSPResponse> &response);
    void addAuthentication(AString *request);

    status_t findPendingRequest(
            const sp<ARTSPResponse> &response, ssize_t *index) const;

    static bool ParseSingleUnsignedLong(
            const char *from, unsigned long *x);

    DISALLOW_EVIL_CONSTRUCTORS(ARTSPConnection);
};

}  // namespace android

#endif  // A_RTSP_CONNECTION_H_
