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

#ifndef MY_TRANSMITTER_H_

#define MY_TRANSMITTER_H_

#include "ARTPConnection.h"

#include <arpa/inet.h>
#include <sys/socket.h>

#include <openssl/md5.h>

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/base64.h>
#include <media/stagefright/foundation/hexdump.h>

#ifdef ANDROID
#include "VideoSource.h"

#include <media/stagefright/OMXClient.h>
#include <media/stagefright/OMXCodec.h>
#endif

namespace android {

#define TRACK_SUFFIX    "trackid=1"
#define PT              96
#define PT_STR          "96"

#define USERNAME        "bcast"
#define PASSWORD        "test"

static int uniformRand(int limit) {
    return ((double)rand() * limit) / RAND_MAX;
}

static bool GetAttribute(const char *s, const char *key, AString *value) {
    value->clear();

    size_t keyLen = strlen(key);

    for (;;) {
        const char *colonPos = strchr(s, ';');

        size_t len =
            (colonPos == NULL) ? strlen(s) : colonPos - s;

        if (len >= keyLen + 1 && s[keyLen] == '=' && !strncmp(s, key, keyLen)) {
            value->setTo(&s[keyLen + 1], len - keyLen - 1);
            return true;
        }

        if (colonPos == NULL) {
            return false;
        }

        s = colonPos + 1;
    }
}

struct MyTransmitter : public AHandler {
    MyTransmitter(const char *url, const sp<ALooper> &looper)
        : mServerURL(url),
          mLooper(looper),
          mConn(new ARTSPConnection),
          mConnected(false),
          mAuthType(NONE),
          mRTPSocket(-1),
          mRTCPSocket(-1),
          mSourceID(rand()),
          mSeqNo(uniformRand(65536)),
          mRTPTimeBase(rand()),
          mNumSamplesSent(0),
          mNumRTPSent(0),
          mNumRTPOctetsSent(0),
          mLastRTPTime(0),
          mLastNTPTime(0) {
        mStreamURL = mServerURL;
        mStreamURL.append("/bazong.sdp");

        mTrackURL = mStreamURL;
        mTrackURL.append("/");
        mTrackURL.append(TRACK_SUFFIX);

        mLooper->registerHandler(this);
        mLooper->registerHandler(mConn);

        sp<AMessage> reply = new AMessage('conn', id());
        mConn->connect(mServerURL.c_str(), reply);

#ifdef ANDROID
        int width = 640;
        int height = 480;

        sp<MediaSource> source = new VideoSource(width, height);

        sp<MetaData> encMeta = new MetaData;
        encMeta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);
        encMeta->setInt32(kKeyWidth, width);
        encMeta->setInt32(kKeyHeight, height);

        OMXClient client;
        client.connect();

        mEncoder = OMXCodec::Create(
                client.interface(), encMeta,
                true /* createEncoder */, source);

        mEncoder->start();

        MediaBuffer *buffer;
        CHECK_EQ(mEncoder->read(&buffer), (status_t)OK);
        CHECK(buffer != NULL);

        makeH264SPropParamSets(buffer);

        buffer->release();
        buffer = NULL;
#endif
    }

    uint64_t ntpTime() {
        struct timeval tv;
        gettimeofday(&tv, NULL);

        uint64_t nowUs = tv.tv_sec * 1000000ll + tv.tv_usec;

        nowUs += ((70ll * 365 + 17) * 24) * 60 * 60 * 1000000ll;

        uint64_t hi = nowUs / 1000000ll;
        uint64_t lo = ((1ll << 32) * (nowUs % 1000000ll)) / 1000000ll;

        return (hi << 32) | lo;
    }

    void issueAnnounce() {
        AString sdp;
        sdp = "v=0\r\n";

        sdp.append("o=- ");

        uint64_t ntp = ntpTime();
        sdp.append(ntp);
        sdp.append(" ");
        sdp.append(ntp);
        sdp.append(" IN IP4 127.0.0.0\r\n");

        sdp.append(
              "s=Sample\r\n"
              "i=Playing around with ANNOUNCE\r\n"
              "c=IN IP4 ");

        struct in_addr addr;
        addr.s_addr = htonl(mServerIP);

        sdp.append(inet_ntoa(addr));

        sdp.append(
              "\r\n"
              "t=0 0\r\n"
              "a=range:npt=now-\r\n");

#ifdef ANDROID
        sp<MetaData> meta = mEncoder->getFormat();
        int32_t width, height;
        CHECK(meta->findInt32(kKeyWidth, &width));
        CHECK(meta->findInt32(kKeyHeight, &height));

        sdp.append(
              "m=video 0 RTP/AVP " PT_STR "\r\n"
              "b=AS 320000\r\n"
              "a=rtpmap:" PT_STR " H264/90000\r\n");

        sdp.append("a=cliprect 0,0,");
        sdp.append(height);
        sdp.append(",");
        sdp.append(width);
        sdp.append("\r\n");

        sdp.append(
              "a=framesize:" PT_STR " ");
        sdp.append(width);
        sdp.append("-");
        sdp.append(height);
        sdp.append("\r\n");

        sdp.append(
              "a=fmtp:" PT_STR " profile-level-id=42C015;sprop-parameter-sets=");

        sdp.append(mSeqParamSet);
        sdp.append(",");
        sdp.append(mPicParamSet);
        sdp.append(";packetization-mode=1\r\n");
#else
        sdp.append(
                "m=audio 0 RTP/AVP " PT_STR "\r\n"
                "a=rtpmap:" PT_STR " L8/8000/1\r\n");
#endif

        sdp.append("a=control:" TRACK_SUFFIX "\r\n");

        AString request;
        request.append("ANNOUNCE ");
        request.append(mStreamURL);
        request.append(" RTSP/1.0\r\n");

        addAuthentication(&request, "ANNOUNCE", mStreamURL.c_str());

        request.append("Content-Type: application/sdp\r\n");
        request.append("Content-Length: ");
        request.append(sdp.size());
        request.append("\r\n");

        request.append("\r\n");
        request.append(sdp);

        sp<AMessage> reply = new AMessage('anno', id());
        mConn->sendRequest(request.c_str(), reply);
    }

    void H(const AString &s, AString *out) {
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

    void authenticate(const sp<ARTSPResponse> &response) {
        ssize_t i = response->mHeaders.indexOfKey("www-authenticate");
        CHECK_GE(i, 0);

        AString value = response->mHeaders.valueAt(i);

        if (!strncmp(value.c_str(), "Basic", 5)) {
            mAuthType = BASIC;
        } else {
            CHECK(!strncmp(value.c_str(), "Digest", 6));
            mAuthType = DIGEST;

            i = value.find("nonce=");
            CHECK_GE(i, 0);
            CHECK_EQ(value.c_str()[i + 6], '\"');
            ssize_t j = value.find("\"", i + 7);
            CHECK_GE(j, 0);

            mNonce.setTo(value, i + 7, j - i - 7);
        }

        issueAnnounce();
    }

    void addAuthentication(
            AString *request, const char *method, const char *url) {
        if (mAuthType == NONE) {
            return;
        }

        if (mAuthType == BASIC) {
            request->append("Authorization: Basic YmNhc3Q6dGVzdAo=\r\n");
            return;
        }

        CHECK_EQ((int)mAuthType, (int)DIGEST);

        AString A1;
        A1.append(USERNAME);
        A1.append(":");
        A1.append("Streaming Server");
        A1.append(":");
        A1.append(PASSWORD);

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

        request->append("Authorization: Digest ");
        request->append("nonce=\"");
        request->append(mNonce);
        request->append("\", ");
        request->append("username=\"" USERNAME "\", ");
        request->append("uri=\"");
        request->append(url);
        request->append("\", ");
        request->append("response=\"");
        request->append(digest);
        request->append("\"");
        request->append("\r\n");
    }

    virtual void onMessageReceived(const sp<AMessage> &msg) {
        switch (msg->what()) {
            case 'conn':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                LOG(INFO) << "connection request completed with result "
                     << result << " (" << strerror(-result) << ")";

                if (result != OK) {
                    (new AMessage('quit', id()))->post();
                    break;
                }

                mConnected = true;

                CHECK(msg->findInt32("server-ip", (int32_t *)&mServerIP));

                issueAnnounce();
                break;
            }

            case 'anno':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                LOG(INFO) << "ANNOUNCE completed with result "
                     << result << " (" << strerror(-result) << ")";

                sp<RefBase> obj;
                CHECK(msg->findObject("response", &obj));
                sp<ARTSPResponse> response;

                if (result == OK) {
                    response = static_cast<ARTSPResponse *>(obj.get());
                    CHECK(response != NULL);

                    if (response->mStatusCode == 401) {
                        if (mAuthType != NONE) {
                            LOG(INFO) << "FAILED to authenticate";
                            (new AMessage('quit', id()))->post();
                            break;
                        }

                        authenticate(response);
                        break;
                    }
                }

                if (result != OK || response->mStatusCode != 200) {
                    (new AMessage('quit', id()))->post();
                    break;
                }

                unsigned rtpPort;
                ARTPConnection::MakePortPair(&mRTPSocket, &mRTCPSocket, &rtpPort);

                // (new AMessage('poll', id()))->post();

                AString request;
                request.append("SETUP ");
                request.append(mTrackURL);
                request.append(" RTSP/1.0\r\n");

                addAuthentication(&request, "SETUP", mTrackURL.c_str());

                request.append("Transport: RTP/AVP;unicast;client_port=");
                request.append(rtpPort);
                request.append("-");
                request.append(rtpPort + 1);
                request.append(";mode=record\r\n");
                request.append("\r\n");

                sp<AMessage> reply = new AMessage('setu', id());
                mConn->sendRequest(request.c_str(), reply);
                break;
            }

#if 0
            case 'poll':
            {
                fd_set rs;
                FD_ZERO(&rs);
                FD_SET(mRTCPSocket, &rs);

                struct timeval tv;
                tv.tv_sec = 0;
                tv.tv_usec = 0;

                int res = select(mRTCPSocket + 1, &rs, NULL, NULL, &tv);

                if (res == 1) {
                    sp<ABuffer> buffer = new ABuffer(65536);
                    ssize_t n = recv(mRTCPSocket, buffer->data(), buffer->size(), 0);

                    if (n <= 0) {
                        LOG(ERROR) << "recv returned " << n;
                    } else {
                        LOG(INFO) << "recv returned " << n << " bytes of data.";

                        hexdump(buffer->data(), n);
                    }
                }

                msg->post(50000);
                break;
            }
#endif

            case 'setu':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                LOG(INFO) << "SETUP completed with result "
                     << result << " (" << strerror(-result) << ")";

                sp<RefBase> obj;
                CHECK(msg->findObject("response", &obj));
                sp<ARTSPResponse> response;

                if (result == OK) {
                    response = static_cast<ARTSPResponse *>(obj.get());
                    CHECK(response != NULL);
                }

                if (result != OK || response->mStatusCode != 200) {
                    (new AMessage('quit', id()))->post();
                    break;
                }

                ssize_t i = response->mHeaders.indexOfKey("session");
                CHECK_GE(i, 0);
                mSessionID = response->mHeaders.valueAt(i);
                i = mSessionID.find(";");
                if (i >= 0) {
                    // Remove options, i.e. ";timeout=90"
                    mSessionID.erase(i, mSessionID.size() - i);
                }

                i = response->mHeaders.indexOfKey("transport");
                CHECK_GE(i, 0);
                AString transport = response->mHeaders.valueAt(i);

                LOG(INFO) << "transport = '" << transport << "'";

                AString value;
                CHECK(GetAttribute(transport.c_str(), "server_port", &value));

                unsigned rtpPort, rtcpPort;
                CHECK_EQ(sscanf(value.c_str(), "%u-%u", &rtpPort, &rtcpPort), 2);

                CHECK(GetAttribute(transport.c_str(), "source", &value));

                memset(mRemoteAddr.sin_zero, 0, sizeof(mRemoteAddr.sin_zero));
                mRemoteAddr.sin_family = AF_INET;
                mRemoteAddr.sin_addr.s_addr = inet_addr(value.c_str());
                mRemoteAddr.sin_port = htons(rtpPort);

                mRemoteRTCPAddr = mRemoteAddr;
                mRemoteRTCPAddr.sin_port = htons(rtpPort + 1);

                CHECK_EQ(0, connect(mRTPSocket,
                                    (const struct sockaddr *)&mRemoteAddr,
                                    sizeof(mRemoteAddr)));

                CHECK_EQ(0, connect(mRTCPSocket,
                                    (const struct sockaddr *)&mRemoteRTCPAddr,
                                    sizeof(mRemoteRTCPAddr)));

                uint32_t x = ntohl(mRemoteAddr.sin_addr.s_addr);
                LOG(INFO) << "sending data to "
                     << (x >> 24)
                     << "."
                     << ((x >> 16) & 0xff)
                     << "."
                     << ((x >> 8) & 0xff)
                     << "."
                     << (x & 0xff)
                     << ":"
                     << rtpPort;

                AString request;
                request.append("RECORD ");
                request.append(mStreamURL);
                request.append(" RTSP/1.0\r\n");

                addAuthentication(&request, "RECORD", mStreamURL.c_str());

                request.append("Session: ");
                request.append(mSessionID);
                request.append("\r\n");
                request.append("\r\n");

                sp<AMessage> reply = new AMessage('reco', id());
                mConn->sendRequest(request.c_str(), reply);
                break;
            }

            case 'reco':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                LOG(INFO) << "RECORD completed with result "
                     << result << " (" << strerror(-result) << ")";

                sp<RefBase> obj;
                CHECK(msg->findObject("response", &obj));
                sp<ARTSPResponse> response;

                if (result == OK) {
                    response = static_cast<ARTSPResponse *>(obj.get());
                    CHECK(response != NULL);
                }

                if (result != OK) {
                    (new AMessage('quit', id()))->post();
                    break;
                }

                (new AMessage('more', id()))->post();
                (new AMessage('sr  ', id()))->post();
                (new AMessage('aliv', id()))->post(30000000ll);
                break;
            }

            case 'aliv':
            {
                if (!mConnected) {
                    break;
                }

                AString request;
                request.append("OPTIONS ");
                request.append(mStreamURL);
                request.append(" RTSP/1.0\r\n");

                addAuthentication(&request, "RECORD", mStreamURL.c_str());

                request.append("Session: ");
                request.append(mSessionID);
                request.append("\r\n");
                request.append("\r\n");

                sp<AMessage> reply = new AMessage('opts', id());
                mConn->sendRequest(request.c_str(), reply);
                break;
            }

            case 'opts':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                LOG(INFO) << "OPTIONS completed with result "
                     << result << " (" << strerror(-result) << ")";

                if (!mConnected) {
                    break;
                }

                (new AMessage('aliv', id()))->post(30000000ll);
                break;
            }

            case 'more':
            {
                if (!mConnected) {
                    break;
                }

                sp<ABuffer> buffer = new ABuffer(65536);
                uint8_t *data = buffer->data();
                data[0] = 0x80;
                data[1] = (1 << 7) | PT;  // M-bit
                data[2] = (mSeqNo >> 8) & 0xff;
                data[3] = mSeqNo & 0xff;
                data[8] = mSourceID >> 24;
                data[9] = (mSourceID >> 16) & 0xff;
                data[10] = (mSourceID >> 8) & 0xff;
                data[11] = mSourceID & 0xff;

#ifdef ANDROID
                MediaBuffer *mediaBuf = NULL;
                for (;;) {
                    CHECK_EQ(mEncoder->read(&mediaBuf), (status_t)OK);
                    if (mediaBuf->range_length() > 0) {
                        break;
                    }
                    mediaBuf->release();
                    mediaBuf = NULL;
                }

                int64_t timeUs;
                CHECK(mediaBuf->meta_data()->findInt64(kKeyTime, &timeUs));

                uint32_t rtpTime = mRTPTimeBase + (timeUs * 9 / 100ll);

                const uint8_t *mediaData =
                    (const uint8_t *)mediaBuf->data() + mediaBuf->range_offset();

                CHECK(!memcmp("\x00\x00\x00\x01", mediaData, 4));

                CHECK_LE(mediaBuf->range_length() - 4 + 12, buffer->size());

                memcpy(&data[12],
                       mediaData + 4, mediaBuf->range_length() - 4);

                buffer->setRange(0, mediaBuf->range_length() - 4 + 12);

                mediaBuf->release();
                mediaBuf = NULL;
#else
                uint32_t rtpTime = mRTPTimeBase + mNumRTPSent * 128;
                memset(&data[12], 0, 128);
                buffer->setRange(0, 12 + 128);
#endif

                data[4] = rtpTime >> 24;
                data[5] = (rtpTime >> 16) & 0xff;
                data[6] = (rtpTime >> 8) & 0xff;
                data[7] = rtpTime & 0xff;

                ssize_t n = send(
                        mRTPSocket, data, buffer->size(), 0);
                if (n < 0) {
                    LOG(ERROR) << "send failed (" << strerror(errno) << ")";
                }
                CHECK_EQ(n, (ssize_t)buffer->size());

                ++mSeqNo;

                ++mNumRTPSent;
                mNumRTPOctetsSent += buffer->size() - 12;

                mLastRTPTime = rtpTime;
                mLastNTPTime = ntpTime();

#ifdef ANDROID
                if (mNumRTPSent < 60 * 25) {  // 60 secs worth
                    msg->post(40000);
#else
                if (mNumRTPOctetsSent < 8000 * 60) {
                    msg->post(1000000ll * 128 / 8000);
#endif
                } else {
                    LOG(INFO) << "That's enough, pausing.";

                    AString request;
                    request.append("PAUSE ");
                    request.append(mStreamURL);
                    request.append(" RTSP/1.0\r\n");

                    addAuthentication(&request, "PAUSE", mStreamURL.c_str());

                    request.append("Session: ");
                    request.append(mSessionID);
                    request.append("\r\n");
                    request.append("\r\n");

                    sp<AMessage> reply = new AMessage('paus', id());
                    mConn->sendRequest(request.c_str(), reply);
                }
                break;
            }

            case 'sr  ':
            {
                if (!mConnected) {
                    break;
                }

                sp<ABuffer> buffer = new ABuffer(65536);
                buffer->setRange(0, 0);

                addSR(buffer);
                addSDES(buffer);

                uint8_t *data = buffer->data();
                ssize_t n = send(
                        mRTCPSocket, data, buffer->size(), 0);
                CHECK_EQ(n, (ssize_t)buffer->size());

                msg->post(3000000);
                break;
            }

            case 'paus':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                LOG(INFO) << "PAUSE completed with result "
                     << result << " (" << strerror(-result) << ")";

                sp<RefBase> obj;
                CHECK(msg->findObject("response", &obj));
                sp<ARTSPResponse> response;

                AString request;
                request.append("TEARDOWN ");
                request.append(mStreamURL);
                request.append(" RTSP/1.0\r\n");

                addAuthentication(&request, "TEARDOWN", mStreamURL.c_str());

                request.append("Session: ");
                request.append(mSessionID);
                request.append("\r\n");
                request.append("\r\n");

                sp<AMessage> reply = new AMessage('tear', id());
                mConn->sendRequest(request.c_str(), reply);
                break;
            }

            case 'tear':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                LOG(INFO) << "TEARDOWN completed with result "
                     << result << " (" << strerror(-result) << ")";

                sp<RefBase> obj;
                CHECK(msg->findObject("response", &obj));
                sp<ARTSPResponse> response;

                if (result == OK) {
                    response = static_cast<ARTSPResponse *>(obj.get());
                    CHECK(response != NULL);
                }

                (new AMessage('quit', id()))->post();
                break;
            }

            case 'disc':
            {
                LOG(INFO) << "disconnect completed";

                mConnected = false;
                (new AMessage('quit', id()))->post();
                break;
            }

            case 'quit':
            {
                if (mConnected) {
                    mConn->disconnect(new AMessage('disc', id()));
                    break;
                }

                if (mRTPSocket >= 0) {
                    close(mRTPSocket);
                    mRTPSocket = -1;
                }

                if (mRTCPSocket >= 0) {
                    close(mRTCPSocket);
                    mRTCPSocket = -1;
                }

#ifdef ANDROID
                mEncoder->stop();
                mEncoder.clear();
#endif

                mLooper->stop();
                break;
            }

            default:
                TRESPASS();
        }
    }

protected:
    virtual ~MyTransmitter() {
    }

private:
    enum AuthType {
        NONE,
        BASIC,
        DIGEST
    };

    AString mServerURL;
    AString mTrackURL;
    AString mStreamURL;

    sp<ALooper> mLooper;
    sp<ARTSPConnection> mConn;
    bool mConnected;
    uint32_t mServerIP;
    AuthType mAuthType;
    AString mNonce;
    AString mSessionID;
    int mRTPSocket, mRTCPSocket;
    uint32_t mSourceID;
    uint32_t mSeqNo;
    uint32_t mRTPTimeBase;
    struct sockaddr_in mRemoteAddr;
    struct sockaddr_in mRemoteRTCPAddr;
    size_t mNumSamplesSent;
    uint32_t mNumRTPSent;
    uint32_t mNumRTPOctetsSent;
    uint32_t mLastRTPTime;
    uint64_t mLastNTPTime;

#ifdef ANDROID
    sp<MediaSource> mEncoder;
    AString mSeqParamSet;
    AString mPicParamSet;

    void makeH264SPropParamSets(MediaBuffer *buffer) {
        static const char kStartCode[] = "\x00\x00\x00\x01";

        const uint8_t *data =
            (const uint8_t *)buffer->data() + buffer->range_offset();
        size_t size = buffer->range_length();

        CHECK_GE(size, 0u);
        CHECK(!memcmp(kStartCode, data, 4));

        data += 4;
        size -= 4;

        size_t startCodePos = 0;
        while (startCodePos + 3 < size
                && memcmp(kStartCode, &data[startCodePos], 4)) {
            ++startCodePos;
        }

        CHECK_LT(startCodePos + 3, size);

        encodeBase64(data, startCodePos, &mSeqParamSet);

        encodeBase64(&data[startCodePos + 4], size - startCodePos - 4,
                     &mPicParamSet);
    }
#endif

    void addSR(const sp<ABuffer> &buffer) {
        uint8_t *data = buffer->data() + buffer->size();

        data[0] = 0x80 | 0;
        data[1] = 200;  // SR
        data[2] = 0;
        data[3] = 6;
        data[4] = mSourceID >> 24;
        data[5] = (mSourceID >> 16) & 0xff;
        data[6] = (mSourceID >> 8) & 0xff;
        data[7] = mSourceID & 0xff;

        data[8] = mLastNTPTime >> (64 - 8);
        data[9] = (mLastNTPTime >> (64 - 16)) & 0xff;
        data[10] = (mLastNTPTime >> (64 - 24)) & 0xff;
        data[11] = (mLastNTPTime >> 32) & 0xff;
        data[12] = (mLastNTPTime >> 24) & 0xff;
        data[13] = (mLastNTPTime >> 16) & 0xff;
        data[14] = (mLastNTPTime >> 8) & 0xff;
        data[15] = mLastNTPTime & 0xff;

        data[16] = (mLastRTPTime >> 24) & 0xff;
        data[17] = (mLastRTPTime >> 16) & 0xff;
        data[18] = (mLastRTPTime >> 8) & 0xff;
        data[19] = mLastRTPTime & 0xff;

        data[20] = mNumRTPSent >> 24;
        data[21] = (mNumRTPSent >> 16) & 0xff;
        data[22] = (mNumRTPSent >> 8) & 0xff;
        data[23] = mNumRTPSent & 0xff;

        data[24] = mNumRTPOctetsSent >> 24;
        data[25] = (mNumRTPOctetsSent >> 16) & 0xff;
        data[26] = (mNumRTPOctetsSent >> 8) & 0xff;
        data[27] = mNumRTPOctetsSent & 0xff;

        buffer->setRange(buffer->offset(), buffer->size() + 28);
    }

    void addSDES(const sp<ABuffer> &buffer) {
        uint8_t *data = buffer->data() + buffer->size();
        data[0] = 0x80 | 1;
        data[1] = 202;  // SDES
        data[4] = mSourceID >> 24;
        data[5] = (mSourceID >> 16) & 0xff;
        data[6] = (mSourceID >> 8) & 0xff;
        data[7] = mSourceID & 0xff;

        size_t offset = 8;

        data[offset++] = 1;  // CNAME

        static const char *kCNAME = "andih@laptop";
        data[offset++] = strlen(kCNAME);

        memcpy(&data[offset], kCNAME, strlen(kCNAME));
        offset += strlen(kCNAME);

        data[offset++] = 7;  // NOTE

        static const char *kNOTE = "Hell's frozen over.";
        data[offset++] = strlen(kNOTE);

        memcpy(&data[offset], kNOTE, strlen(kNOTE));
        offset += strlen(kNOTE);

        data[offset++] = 0;

        if ((offset % 4) > 0) {
            size_t count = 4 - (offset % 4);
            switch (count) {
                case 3:
                    data[offset++] = 0;
                case 2:
                    data[offset++] = 0;
                case 1:
                    data[offset++] = 0;
            }
        }

        size_t numWords = (offset / 4) - 1;
        data[2] = numWords >> 8;
        data[3] = numWords & 0xff;

        buffer->setRange(buffer->offset(), buffer->size() + offset);
    }

    DISALLOW_EVIL_CONSTRUCTORS(MyTransmitter);
};

}  // namespace android

#endif  // MY_TRANSMITTER_H_
