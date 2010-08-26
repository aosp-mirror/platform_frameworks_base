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

#include "ARTSPConnection.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaErrors.h>

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <sys/socket.h>

namespace android {

// static
const int64_t ARTSPConnection::kSelectTimeoutUs = 1000ll;

ARTSPConnection::ARTSPConnection()
    : mState(DISCONNECTED),
      mSocket(-1),
      mConnectionID(0),
      mNextCSeq(0),
      mReceiveResponseEventPending(false) {
}

ARTSPConnection::~ARTSPConnection() {
    if (mSocket >= 0) {
        LOG(ERROR) << "Connection is still open, closing the socket.";
        close(mSocket);
        mSocket = -1;
    }
}

void ARTSPConnection::connect(const char *url, const sp<AMessage> &reply) {
    sp<AMessage> msg = new AMessage(kWhatConnect, id());
    msg->setString("url", url);
    msg->setMessage("reply", reply);
    msg->post();
}

void ARTSPConnection::disconnect(const sp<AMessage> &reply) {
    sp<AMessage> msg = new AMessage(kWhatDisconnect, id());
    msg->setMessage("reply", reply);
    msg->post();
}

void ARTSPConnection::sendRequest(
        const char *request, const sp<AMessage> &reply) {
    sp<AMessage> msg = new AMessage(kWhatSendRequest, id());
    msg->setString("request", request);
    msg->setMessage("reply", reply);
    msg->post();
}

void ARTSPConnection::observeBinaryData(const sp<AMessage> &reply) {
    sp<AMessage> msg = new AMessage(kWhatObserveBinaryData, id());
    msg->setMessage("reply", reply);
    msg->post();
}

void ARTSPConnection::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatConnect:
            onConnect(msg);
            break;

        case kWhatDisconnect:
            onDisconnect(msg);
            break;

        case kWhatCompleteConnection:
            onCompleteConnection(msg);
            break;

        case kWhatSendRequest:
            onSendRequest(msg);
            break;

        case kWhatReceiveResponse:
            onReceiveResponse();
            break;

        case kWhatObserveBinaryData:
        {
            CHECK(msg->findMessage("reply", &mObserveBinaryMessage));
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

// static
bool ARTSPConnection::ParseURL(
        const char *url, AString *host, unsigned *port, AString *path) {
    host->clear();
    *port = 0;
    path->clear();

    if (strncasecmp("rtsp://", url, 7)) {
        return false;
    }

    const char *slashPos = strchr(&url[7], '/');

    if (slashPos == NULL) {
        host->setTo(&url[7]);
        path->setTo("/");
    } else {
        host->setTo(&url[7], slashPos - &url[7]);
        path->setTo(slashPos);
    }

    char *colonPos = strchr(host->c_str(), ':');

    if (colonPos != NULL) {
        unsigned long x;
        if (!ParseSingleUnsignedLong(colonPos + 1, &x) || x >= 65536) {
            return false;
        }

        *port = x;

        size_t colonOffset = colonPos - host->c_str();
        size_t trailing = host->size() - colonOffset;
        host->erase(colonOffset, trailing);
    } else {
        *port = 554;
    }

    return true;
}

static void MakeSocketBlocking(int s, bool blocking) {
    // Make socket non-blocking.
    int flags = fcntl(s, F_GETFL, 0);
    CHECK_NE(flags, -1);

    if (blocking) {
        flags &= ~O_NONBLOCK;
    } else {
        flags |= O_NONBLOCK;
    }

    CHECK_NE(fcntl(s, F_SETFL, flags), -1);
}

void ARTSPConnection::onConnect(const sp<AMessage> &msg) {
    ++mConnectionID;

    if (mState != DISCONNECTED) {
        close(mSocket);
        mSocket = -1;

        flushPendingRequests();
    }

    mState = CONNECTING;

    mSocket = socket(AF_INET, SOCK_STREAM, 0);

    MakeSocketBlocking(mSocket, false);

    AString url;
    CHECK(msg->findString("url", &url));

    AString host, path;
    unsigned port;
    CHECK(ParseURL(url.c_str(), &host, &port, &path));

    struct hostent *ent = gethostbyname(host.c_str());
    CHECK(ent != NULL);

    struct sockaddr_in remote;
    memset(remote.sin_zero, 0, sizeof(remote.sin_zero));
    remote.sin_family = AF_INET;
    remote.sin_addr.s_addr = *(in_addr_t *)ent->h_addr;
    remote.sin_port = htons(port);

    int err = ::connect(
            mSocket, (const struct sockaddr *)&remote, sizeof(remote));

    sp<AMessage> reply;
    CHECK(msg->findMessage("reply", &reply));

    reply->setInt32("server-ip", ntohl(remote.sin_addr.s_addr));

    if (err < 0) {
        if (errno == EINPROGRESS) {
            sp<AMessage> msg = new AMessage(kWhatCompleteConnection, id());
            msg->setMessage("reply", reply);
            msg->setInt32("connection-id", mConnectionID);
            msg->post();
            return;
        }

        reply->setInt32("result", -errno);
        mState = DISCONNECTED;

        close(mSocket);
        mSocket = -1;
    } else {
        reply->setInt32("result", OK);
        mState = CONNECTED;
        mNextCSeq = 1;

        postReceiveReponseEvent();
    }

    reply->post();
}

void ARTSPConnection::onDisconnect(const sp<AMessage> &msg) {
    if (mState == CONNECTED || mState == CONNECTING) {
        close(mSocket);
        mSocket = -1;

        flushPendingRequests();
    }

    sp<AMessage> reply;
    CHECK(msg->findMessage("reply", &reply));

    reply->setInt32("result", OK);
    mState = DISCONNECTED;

    reply->post();
}

void ARTSPConnection::onCompleteConnection(const sp<AMessage> &msg) {
    sp<AMessage> reply;
    CHECK(msg->findMessage("reply", &reply));

    int32_t connectionID;
    CHECK(msg->findInt32("connection-id", &connectionID));

    if ((connectionID != mConnectionID) || mState != CONNECTING) {
        // While we were attempting to connect, the attempt was
        // cancelled.
        reply->setInt32("result", -ECONNABORTED);
        reply->post();
        return;
    }

    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = kSelectTimeoutUs;

    fd_set ws;
    FD_ZERO(&ws);
    FD_SET(mSocket, &ws);

    int res = select(mSocket + 1, NULL, &ws, NULL, &tv);
    CHECK_GE(res, 0);

    if (res == 0) {
        // Timed out. Not yet connected.

        msg->post();
        return;
    }

    int err;
    socklen_t optionLen = sizeof(err);
    CHECK_EQ(getsockopt(mSocket, SOL_SOCKET, SO_ERROR, &err, &optionLen), 0);
    CHECK_EQ(optionLen, (socklen_t)sizeof(err));

    if (err != 0) {
        LOG(ERROR) << "err = " << err << " (" << strerror(err) << ")";

        reply->setInt32("result", -err);

        mState = DISCONNECTED;
        close(mSocket);
        mSocket = -1;
    } else {
        reply->setInt32("result", OK);
        mState = CONNECTED;
        mNextCSeq = 1;

        postReceiveReponseEvent();
    }

    reply->post();
}

void ARTSPConnection::onSendRequest(const sp<AMessage> &msg) {
    sp<AMessage> reply;
    CHECK(msg->findMessage("reply", &reply));

    if (mState != CONNECTED) {
        reply->setInt32("result", -ENOTCONN);
        reply->post();
        return;
    }

    AString request;
    CHECK(msg->findString("request", &request));

    // Find the boundary between headers and the body.
    ssize_t i = request.find("\r\n\r\n");
    CHECK_GE(i, 0);

    int32_t cseq = mNextCSeq++;

    AString cseqHeader = "CSeq: ";
    cseqHeader.append(cseq);
    cseqHeader.append("\r\n");

    request.insert(cseqHeader, i + 2);

    LOG(VERBOSE) << request;

    size_t numBytesSent = 0;
    while (numBytesSent < request.size()) {
        ssize_t n =
            send(mSocket, request.c_str() + numBytesSent,
                 request.size() - numBytesSent, 0);

        if (n == 0) {
            // Server closed the connection.
            TRESPASS();
        } else if (n < 0) {
            if (errno == EINTR) {
                continue;
            }

            TRESPASS();
        }

        numBytesSent += (size_t)n;
    }

    mPendingRequests.add(cseq, reply);
}

void ARTSPConnection::onReceiveResponse() {
    mReceiveResponseEventPending = false;

    if (mState != CONNECTED) {
        return;
    }

    struct timeval tv;
    tv.tv_sec = 0;
    tv.tv_usec = kSelectTimeoutUs;

    fd_set rs;
    FD_ZERO(&rs);
    FD_SET(mSocket, &rs);

    int res = select(mSocket + 1, &rs, NULL, NULL, &tv);
    CHECK_GE(res, 0);

    if (res == 1) {
        MakeSocketBlocking(mSocket, true);

        bool success = receiveRTSPReponse();

        MakeSocketBlocking(mSocket, false);

        if (!success) {
            // Something horrible, irreparable has happened.
            flushPendingRequests();
            return;
        }
    }

    postReceiveReponseEvent();
}

void ARTSPConnection::flushPendingRequests() {
    for (size_t i = 0; i < mPendingRequests.size(); ++i) {
        sp<AMessage> reply = mPendingRequests.valueAt(i);

        reply->setInt32("result", -ECONNABORTED);
        reply->post();
    }

    mPendingRequests.clear();
}

void ARTSPConnection::postReceiveReponseEvent() {
    if (mReceiveResponseEventPending) {
        return;
    }

    sp<AMessage> msg = new AMessage(kWhatReceiveResponse, id());
    msg->post();

    mReceiveResponseEventPending = true;
}

status_t ARTSPConnection::receive(void *data, size_t size) {
    size_t offset = 0;
    while (offset < size) {
        ssize_t n = recv(mSocket, (uint8_t *)data + offset, size - offset, 0);
        if (n == 0) {
            // Server closed the connection.
            return ERROR_IO;
        } else if (n < 0) {
            if (errno == EINTR) {
                continue;
            }

            TRESPASS();
        }

        offset += (size_t)n;
    }

    return OK;
}

bool ARTSPConnection::receiveLine(AString *line) {
    line->clear();

    bool sawCR = false;
    for (;;) {
        char c;
        if (receive(&c, 1) != OK) {
            return false;
        }

        if (sawCR && c == '\n') {
            line->erase(line->size() - 1, 1);
            return true;
        }

        line->append(&c, 1);

        if (c == '$' && line->size() == 1) {
            // Special-case for interleaved binary data.
            return true;
        }

        sawCR = (c == '\r');
    }
}

sp<ABuffer> ARTSPConnection::receiveBinaryData() {
    uint8_t x[3];
    if (receive(x, 3) != OK) {
        return NULL;
    }

    sp<ABuffer> buffer = new ABuffer((x[1] << 8) | x[2]);
    if (receive(buffer->data(), buffer->size()) != OK) {
        return NULL;
    }

    buffer->meta()->setInt32("index", (int32_t)x[0]);

    return buffer;
}

bool ARTSPConnection::receiveRTSPReponse() {
    AString statusLine;

    if (!receiveLine(&statusLine)) {
        return false;
    }

    if (statusLine == "$") {
        sp<ABuffer> buffer = receiveBinaryData();

        if (buffer == NULL) {
            return false;
        }

        if (mObserveBinaryMessage != NULL) {
            sp<AMessage> notify = mObserveBinaryMessage->dup();
            notify->setObject("buffer", buffer);
            notify->post();
        } else {
            LOG(WARNING) << "received binary data, but no one cares.";
        }

        return true;
    }

    sp<ARTSPResponse> response = new ARTSPResponse;
    response->mStatusLine = statusLine;

    LOG(INFO) << "status: " << response->mStatusLine;

    ssize_t space1 = response->mStatusLine.find(" ");
    if (space1 < 0) {
        return false;
    }
    ssize_t space2 = response->mStatusLine.find(" ", space1 + 1);
    if (space2 < 0) {
        return false;
    }

    AString statusCodeStr(
            response->mStatusLine, space1 + 1, space2 - space1 - 1);

    if (!ParseSingleUnsignedLong(
                statusCodeStr.c_str(), &response->mStatusCode)
            || response->mStatusCode < 100 || response->mStatusCode > 999) {
        return false;
    }

    AString line;
    for (;;) {
        if (!receiveLine(&line)) {
            break;
        }

        if (line.empty()) {
            break;
        }

        LOG(VERBOSE) << "line: " << line;

        ssize_t colonPos = line.find(":");
        if (colonPos < 0) {
            // Malformed header line.
            return false;
        }

        AString key(line, 0, colonPos);
        key.trim();
        key.tolower();

        line.erase(0, colonPos + 1);
        line.trim();

        response->mHeaders.add(key, line);
    }

    unsigned long contentLength = 0;

    ssize_t i = response->mHeaders.indexOfKey("content-length");

    if (i >= 0) {
        AString value = response->mHeaders.valueAt(i);
        if (!ParseSingleUnsignedLong(value.c_str(), &contentLength)) {
            return false;
        }
    }

    if (contentLength > 0) {
        response->mContent = new ABuffer(contentLength);

        size_t numBytesRead = 0;
        while (numBytesRead < contentLength) {
            ssize_t n = recv(
                    mSocket, response->mContent->data() + numBytesRead,
                    contentLength - numBytesRead, 0);

            if (n == 0) {
                // Server closed the connection.
                TRESPASS();
            } else if (n < 0) {
                if (errno == EINTR) {
                    continue;
                }

                TRESPASS();
            }

            numBytesRead += (size_t)n;
        }
    }

    return notifyResponseListener(response);
}

// static
bool ARTSPConnection::ParseSingleUnsignedLong(
        const char *from, unsigned long *x) {
    char *end;
    *x = strtoul(from, &end, 10);

    if (end == from || *end != '\0') {
        return false;
    }

    return true;
}

bool ARTSPConnection::notifyResponseListener(
        const sp<ARTSPResponse> &response) {
    ssize_t i = response->mHeaders.indexOfKey("cseq");

    if (i < 0) {
        return true;
    }

    AString value = response->mHeaders.valueAt(i);

    unsigned long cseq;
    if (!ParseSingleUnsignedLong(value.c_str(), &cseq)) {
        return false;
    }

    i = mPendingRequests.indexOfKey(cseq);

    if (i < 0) {
        // Unsolicited response?
        TRESPASS();
    }

    sp<AMessage> reply = mPendingRequests.valueAt(i);
    mPendingRequests.removeItemsAt(i);

    reply->setInt32("result", OK);
    reply->setObject("response", response);
    reply->post();

    return true;
}

}  // namespace android
