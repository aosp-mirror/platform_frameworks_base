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
#define LOG_TAG "ARTSPConnection"
#include <utils/Log.h>

#include "ARTSPConnection.h"

#include <cutils/properties.h>

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/base64.h>
#include <media/stagefright/MediaErrors.h>

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <openssl/md5.h>
#include <sys/socket.h>

#include "HTTPBase.h"

namespace android {

// static
const int64_t ARTSPConnection::kSelectTimeoutUs = 1000ll;

ARTSPConnection::ARTSPConnection(bool uidValid, uid_t uid)
    : mUIDValid(uidValid),
      mUID(uid),
      mState(DISCONNECTED),
      mAuthType(NONE),
      mSocket(-1),
      mConnectionID(0),
      mNextCSeq(0),
      mReceiveResponseEventPending(false) {
    MakeUserAgent(&mUserAgent);
}

ARTSPConnection::~ARTSPConnection() {
    if (mSocket >= 0) {
        ALOGE("Connection is still open, closing the socket.");
        if (mUIDValid) {
            HTTPBase::UnRegisterSocketUserTag(mSocket);
        }
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
        const char *url, AString *host, unsigned *port, AString *path,
        AString *user, AString *pass) {
    host->clear();
    *port = 0;
    path->clear();
    user->clear();
    pass->clear();

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

    ssize_t atPos = host->find("@");

    if (atPos >= 0) {
        // Split of user:pass@ from hostname.

        AString userPass(*host, 0, atPos);
        host->erase(0, atPos + 1);

        ssize_t colonPos = userPass.find(":");

        if (colonPos < 0) {
            *user = userPass;
        } else {
            user->setTo(userPass, 0, colonPos);
            pass->setTo(userPass, colonPos + 1, userPass.size() - colonPos - 1);
        }
    }

    const char *colonPos = strchr(host->c_str(), ':');

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

static status_t MakeSocketBlocking(int s, bool blocking) {
    // Make socket non-blocking.
    int flags = fcntl(s, F_GETFL, 0);

    if (flags == -1) {
        return UNKNOWN_ERROR;
    }

    if (blocking) {
        flags &= ~O_NONBLOCK;
    } else {
        flags |= O_NONBLOCK;
    }

    flags = fcntl(s, F_SETFL, flags);

    return flags == -1 ? UNKNOWN_ERROR : OK;
}

void ARTSPConnection::onConnect(const sp<AMessage> &msg) {
    ++mConnectionID;

    if (mState != DISCONNECTED) {
        if (mUIDValid) {
            HTTPBase::UnRegisterSocketUserTag(mSocket);
        }
        close(mSocket);
        mSocket = -1;

        flushPendingRequests();
    }

    mState = CONNECTING;

    AString url;
    CHECK(msg->findString("url", &url));

    sp<AMessage> reply;
    CHECK(msg->findMessage("reply", &reply));

    AString host, path;
    unsigned port;
    if (!ParseURL(url.c_str(), &host, &port, &path, &mUser, &mPass)
            || (mUser.size() > 0 && mPass.size() == 0)) {
        // If we have a user name but no password we have to give up
        // right here, since we currently have no way of asking the user
        // for this information.

        ALOGE("Malformed rtsp url %s", url.c_str());

        reply->setInt32("result", ERROR_MALFORMED);
        reply->post();

        mState = DISCONNECTED;
        return;
    }

    if (mUser.size() > 0) {
        ALOGV("user = '%s', pass = '%s'", mUser.c_str(), mPass.c_str());
    }

    struct hostent *ent = gethostbyname(host.c_str());
    if (ent == NULL) {
        ALOGE("Unknown host %s", host.c_str());

        reply->setInt32("result", -ENOENT);
        reply->post();

        mState = DISCONNECTED;
        return;
    }

    mSocket = socket(AF_INET, SOCK_STREAM, 0);

    if (mUIDValid) {
        HTTPBase::RegisterSocketUserTag(mSocket, mUID,
                                        (uint32_t)*(uint32_t*) "RTSP");
    }

    MakeSocketBlocking(mSocket, false);

    struct sockaddr_in remote;
    memset(remote.sin_zero, 0, sizeof(remote.sin_zero));
    remote.sin_family = AF_INET;
    remote.sin_addr.s_addr = *(in_addr_t *)ent->h_addr;
    remote.sin_port = htons(port);

    int err = ::connect(
            mSocket, (const struct sockaddr *)&remote, sizeof(remote));

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

        if (mUIDValid) {
            HTTPBase::UnRegisterSocketUserTag(mSocket);
        }
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

void ARTSPConnection::performDisconnect() {
    if (mUIDValid) {
        HTTPBase::UnRegisterSocketUserTag(mSocket);
    }
    close(mSocket);
    mSocket = -1;

    flushPendingRequests();

    mUser.clear();
    mPass.clear();
    mAuthType = NONE;
    mNonce.clear();

    mState = DISCONNECTED;
}

void ARTSPConnection::onDisconnect(const sp<AMessage> &msg) {
    if (mState == CONNECTED || mState == CONNECTING) {
        performDisconnect();
    }

    sp<AMessage> reply;
    CHECK(msg->findMessage("reply", &reply));

    reply->setInt32("result", OK);

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
        ALOGE("err = %d (%s)", err, strerror(err));

        reply->setInt32("result", -err);

        mState = DISCONNECTED;
        if (mUIDValid) {
            HTTPBase::UnRegisterSocketUserTag(mSocket);
        }
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

    // Just in case we need to re-issue the request with proper authentication
    // later, stash it away.
    reply->setString("original-request", request.c_str(), request.size());

    addAuthentication(&request);
    addUserAgent(&request);

    // Find the boundary between headers and the body.
    ssize_t i = request.find("\r\n\r\n");
    CHECK_GE(i, 0);

    int32_t cseq = mNextCSeq++;

    AString cseqHeader = "CSeq: ";
    cseqHeader.append(cseq);
    cseqHeader.append("\r\n");

    request.insert(cseqHeader, i + 2);

    ALOGV("request: '%s'", request.c_str());

    size_t numBytesSent = 0;
    while (numBytesSent < request.size()) {
        ssize_t n =
            send(mSocket, request.c_str() + numBytesSent,
                 request.size() - numBytesSent, 0);

        if (n < 0 && errno == EINTR) {
            continue;
        }

        if (n <= 0) {
            performDisconnect();

            if (n == 0) {
                // Server closed the connection.
                ALOGE("Server unexpectedly closed the connection.");

                reply->setInt32("result", ERROR_IO);
                reply->post();
            } else {
                ALOGE("Error sending rtsp request. (%s)", strerror(errno));
                reply->setInt32("result", -errno);
                reply->post();
            }

            return;
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

        if (n < 0 && errno == EINTR) {
            continue;
        }

        if (n <= 0) {
            performDisconnect();

            if (n == 0) {
                // Server closed the connection.
                ALOGE("Server unexpectedly closed the connection.");
                return ERROR_IO;
            } else {
                ALOGE("Error reading rtsp response. (%s)", strerror(errno));
                return -errno;
            }
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

static bool IsRTSPVersion(const AString &s) {
    return s == "RTSP/1.0";
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
            notify->setBuffer("buffer", buffer);
            notify->post();
        } else {
            ALOGW("received binary data, but no one cares.");
        }

        return true;
    }

    sp<ARTSPResponse> response = new ARTSPResponse;
    response->mStatusLine = statusLine;

    ALOGI("status: %s", response->mStatusLine.c_str());

    ssize_t space1 = response->mStatusLine.find(" ");
    if (space1 < 0) {
        return false;
    }
    ssize_t space2 = response->mStatusLine.find(" ", space1 + 1);
    if (space2 < 0) {
        return false;
    }

    bool isRequest = false;

    if (!IsRTSPVersion(AString(response->mStatusLine, 0, space1))) {
        CHECK(IsRTSPVersion(
                    AString(
                        response->mStatusLine,
                        space2 + 1,
                        response->mStatusLine.size() - space2 - 1)));

        isRequest = true;

        response->mStatusCode = 0;
    } else {
        AString statusCodeStr(
                response->mStatusLine, space1 + 1, space2 - space1 - 1);

        if (!ParseSingleUnsignedLong(
                    statusCodeStr.c_str(), &response->mStatusCode)
                || response->mStatusCode < 100 || response->mStatusCode > 999) {
            return false;
        }
    }

    AString line;
    ssize_t lastDictIndex = -1;
    for (;;) {
        if (!receiveLine(&line)) {
            break;
        }

        if (line.empty()) {
            break;
        }

        ALOGV("line: '%s'", line.c_str());

        if (line.c_str()[0] == ' ' || line.c_str()[0] == '\t') {
            // Support for folded header values.

            if (lastDictIndex < 0) {
                // First line cannot be a continuation of the previous one.
                return false;
            }

            AString &value = response->mHeaders.editValueAt(lastDictIndex);
            value.append(line);

            continue;
        }

        ssize_t colonPos = line.find(":");
        if (colonPos < 0) {
            // Malformed header line.
            return false;
        }

        AString key(line, 0, colonPos);
        key.trim();
        key.tolower();

        line.erase(0, colonPos + 1);

        lastDictIndex = response->mHeaders.add(key, line);
    }

    for (size_t i = 0; i < response->mHeaders.size(); ++i) {
        response->mHeaders.editValueAt(i).trim();
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

        if (receive(response->mContent->data(), contentLength) != OK) {
            return false;
        }
    }

    if (response->mStatusCode == 401) {
        if (mAuthType == NONE && mUser.size() > 0
                && parseAuthMethod(response)) {
            ssize_t i;
            CHECK_EQ((status_t)OK, findPendingRequest(response, &i));
            CHECK_GE(i, 0);

            sp<AMessage> reply = mPendingRequests.valueAt(i);
            mPendingRequests.removeItemsAt(i);

            AString request;
            CHECK(reply->findString("original-request", &request));

            sp<AMessage> msg = new AMessage(kWhatSendRequest, id());
            msg->setMessage("reply", reply);
            msg->setString("request", request.c_str(), request.size());

            ALOGI("re-sending request with authentication headers...");
            onSendRequest(msg);

            return true;
        }
    }

    return isRequest
        ? handleServerRequest(response)
        : notifyResponseListener(response);
}

bool ARTSPConnection::handleServerRequest(const sp<ARTSPResponse> &request) {
    // Implementation of server->client requests is optional for all methods
    // but we do need to respond, even if it's just to say that we don't
    // support the method.

    ssize_t space1 = request->mStatusLine.find(" ");
    CHECK_GE(space1, 0);

    AString response;
    response.append("RTSP/1.0 501 Not Implemented\r\n");

    ssize_t i = request->mHeaders.indexOfKey("cseq");

    if (i >= 0) {
        AString value = request->mHeaders.valueAt(i);

        unsigned long cseq;
        if (!ParseSingleUnsignedLong(value.c_str(), &cseq)) {
            return false;
        }

        response.append("CSeq: ");
        response.append(cseq);
        response.append("\r\n");
    }

    response.append("\r\n");

    size_t numBytesSent = 0;
    while (numBytesSent < response.size()) {
        ssize_t n =
            send(mSocket, response.c_str() + numBytesSent,
                 response.size() - numBytesSent, 0);

        if (n < 0 && errno == EINTR) {
            continue;
        }

        if (n <= 0) {
            if (n == 0) {
                // Server closed the connection.
                ALOGE("Server unexpectedly closed the connection.");
            } else {
                ALOGE("Error sending rtsp response (%s).", strerror(errno));
            }

            performDisconnect();

            return false;
        }

        numBytesSent += (size_t)n;
    }

    return true;
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

status_t ARTSPConnection::findPendingRequest(
        const sp<ARTSPResponse> &response, ssize_t *index) const {
    *index = 0;

    ssize_t i = response->mHeaders.indexOfKey("cseq");

    if (i < 0) {
        // This is an unsolicited server->client message.
        return OK;
    }

    AString value = response->mHeaders.valueAt(i);

    unsigned long cseq;
    if (!ParseSingleUnsignedLong(value.c_str(), &cseq)) {
        return ERROR_MALFORMED;
    }

    i = mPendingRequests.indexOfKey(cseq);

    if (i < 0) {
        return -ENOENT;
    }

    *index = i;

    return OK;
}

bool ARTSPConnection::notifyResponseListener(
        const sp<ARTSPResponse> &response) {
    ssize_t i;
    status_t err = findPendingRequest(response, &i);

    if (err == OK && i < 0) {
        // An unsolicited server response is not a problem.
        return true;
    }

    if (err != OK) {
        return false;
    }

    sp<AMessage> reply = mPendingRequests.valueAt(i);
    mPendingRequests.removeItemsAt(i);

    reply->setInt32("result", OK);
    reply->setObject("response", response);
    reply->post();

    return true;
}

bool ARTSPConnection::parseAuthMethod(const sp<ARTSPResponse> &response) {
    ssize_t i = response->mHeaders.indexOfKey("www-authenticate");

    if (i < 0) {
        return false;
    }

    AString value = response->mHeaders.valueAt(i);

    if (!strncmp(value.c_str(), "Basic", 5)) {
        mAuthType = BASIC;
    } else {
#if !defined(HAVE_ANDROID_OS)
        // We don't have access to the MD5 implementation on the simulator,
        // so we won't support digest authentication.
        return false;
#endif

        CHECK(!strncmp(value.c_str(), "Digest", 6));
        mAuthType = DIGEST;

        i = value.find("nonce=");
        CHECK_GE(i, 0);
        CHECK_EQ(value.c_str()[i + 6], '\"');
        ssize_t j = value.find("\"", i + 7);
        CHECK_GE(j, 0);

        mNonce.setTo(value, i + 7, j - i - 7);
    }

    return true;
}

#if defined(HAVE_ANDROID_OS)
static void H(const AString &s, AString *out) {
    out->clear();

    MD5_CTX m;
    MD5_Init(&m);
    MD5_Update(&m, s.c_str(), s.size());

    uint8_t key[16];
    MD5_Final(key, &m);

    for (size_t i = 0; i < 16; ++i) {
        char nibble = key[i] >> 4;
        if (nibble <= 9) {
            nibble += '0';
        } else {
            nibble += 'a' - 10;
        }
        out->append(&nibble, 1);

        nibble = key[i] & 0x0f;
        if (nibble <= 9) {
            nibble += '0';
        } else {
            nibble += 'a' - 10;
        }
        out->append(&nibble, 1);
    }
}
#endif

static void GetMethodAndURL(
        const AString &request, AString *method, AString *url) {
    ssize_t space1 = request.find(" ");
    CHECK_GE(space1, 0);

    ssize_t space2 = request.find(" ", space1 + 1);
    CHECK_GE(space2, 0);

    method->setTo(request, 0, space1);
    url->setTo(request, space1 + 1, space2 - space1);
}

void ARTSPConnection::addAuthentication(AString *request) {
    if (mAuthType == NONE) {
        return;
    }

    // Find the boundary between headers and the body.
    ssize_t i = request->find("\r\n\r\n");
    CHECK_GE(i, 0);

    if (mAuthType == BASIC) {
        AString tmp;
        tmp.append(mUser);
        tmp.append(":");
        tmp.append(mPass);

        AString out;
        encodeBase64(tmp.c_str(), tmp.size(), &out);

        AString fragment;
        fragment.append("Authorization: Basic ");
        fragment.append(out);
        fragment.append("\r\n");

        request->insert(fragment, i + 2);

        return;
    }

#if defined(HAVE_ANDROID_OS)
    CHECK_EQ((int)mAuthType, (int)DIGEST);

    AString method, url;
    GetMethodAndURL(*request, &method, &url);

    AString A1;
    A1.append(mUser);
    A1.append(":");
    A1.append("Streaming Server");
    A1.append(":");
    A1.append(mPass);

    AString A2;
    A2.append(method);
    A2.append(":");
    A2.append(url);

    AString HA1, HA2;
    H(A1, &HA1);
    H(A2, &HA2);

    AString tmp;
    tmp.append(HA1);
    tmp.append(":");
    tmp.append(mNonce);
    tmp.append(":");
    tmp.append(HA2);

    AString digest;
    H(tmp, &digest);

    AString fragment;
    fragment.append("Authorization: Digest ");
    fragment.append("nonce=\"");
    fragment.append(mNonce);
    fragment.append("\", ");
    fragment.append("username=\"");
    fragment.append(mUser);
    fragment.append("\", ");
    fragment.append("uri=\"");
    fragment.append(url);
    fragment.append("\", ");
    fragment.append("response=\"");
    fragment.append(digest);
    fragment.append("\"");
    fragment.append("\r\n");

    request->insert(fragment, i + 2);
#endif
}

// static
void ARTSPConnection::MakeUserAgent(AString *userAgent) {
    userAgent->clear();
    userAgent->setTo("User-Agent: stagefright/1.1 (Linux;Android ");

#if (PROPERTY_VALUE_MAX < 8)
#error "PROPERTY_VALUE_MAX must be at least 8"
#endif

    char value[PROPERTY_VALUE_MAX];
    property_get("ro.build.version.release", value, "Unknown");
    userAgent->append(value);
    userAgent->append(")\r\n");
}

void ARTSPConnection::addUserAgent(AString *request) const {
    // Find the boundary between headers and the body.
    ssize_t i = request->find("\r\n\r\n");
    CHECK_GE(i, 0);

    request->insert(mUserAgent, i + 2);
}

}  // namespace android
