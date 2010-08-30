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

#ifndef MY_HANDLER_H_

#define MY_HANDLER_H_

#include "APacketSource.h"
#include "ARTPConnection.h"
#include "ARTSPConnection.h"
#include "ASessionDescription.h"

#include <ctype.h>

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/ALooper.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaErrors.h>

// If no access units are received within 3 secs, assume that the rtp
// stream has ended and signal end of stream.
static int64_t kAccessUnitTimeoutUs = 3000000ll;

// If no access units arrive for the first 10 secs after starting the
// stream, assume none ever will and signal EOS or switch transports.
static int64_t kStartupTimeoutUs = 10000000ll;

namespace android {

static bool GetAttribute(const char *s, const char *key, AString *value) {
    value->clear();

    size_t keyLen = strlen(key);

    for (;;) {
        while (isspace(*s)) {
            ++s;
        }

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

struct MyHandler : public AHandler {
    MyHandler(const char *url, const sp<ALooper> &looper)
        : mLooper(looper),
          mNetLooper(new ALooper),
          mConn(new ARTSPConnection),
          mRTPConn(new ARTPConnection),
          mSessionURL(url),
          mSetupTracksSuccessful(false),
          mSeekPending(false),
          mFirstAccessUnit(true),
          mFirstAccessUnitNTP(0),
          mNumAccessUnitsReceived(0),
          mCheckPending(false),
          mTryTCPInterleaving(false) {
        mNetLooper->setName("rtsp net");
        mNetLooper->start(false /* runOnCallingThread */,
                          false /* canCallJava */,
                          PRIORITY_HIGHEST);
    }

    void connect(const sp<AMessage> &doneMsg) {
        mDoneMsg = doneMsg;

        mLooper->registerHandler(this);
        mLooper->registerHandler(mConn);
        (1 ? mNetLooper : mLooper)->registerHandler(mRTPConn);

        sp<AMessage> notify = new AMessage('biny', id());
        mConn->observeBinaryData(notify);

        sp<AMessage> reply = new AMessage('conn', id());
        mConn->connect(mSessionURL.c_str(), reply);
    }

    void disconnect(const sp<AMessage> &doneMsg) {
        mDoneMsg = doneMsg;

        (new AMessage('abor', id()))->post();
    }

    void seek(int64_t timeUs) {
        sp<AMessage> msg = new AMessage('seek', id());
        msg->setInt64("time", timeUs);
        msg->post();
    }

    int64_t getNormalPlayTimeUs() {
        int64_t maxTimeUs = 0;
        for (size_t i = 0; i < mTracks.size(); ++i) {
            int64_t timeUs = mTracks.editItemAt(i).mPacketSource
                ->getNormalPlayTimeUs();

            if (i == 0 || timeUs > maxTimeUs) {
                maxTimeUs = timeUs;
            }
        }

        return maxTimeUs;
    }

    virtual void onMessageReceived(const sp<AMessage> &msg) {
        switch (msg->what()) {
            case 'conn':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                LOG(INFO) << "connection request completed with result "
                     << result << " (" << strerror(-result) << ")";

                if (result == OK) {
                    AString request;
                    request = "DESCRIBE ";
                    request.append(mSessionURL);
                    request.append(" RTSP/1.0\r\n");
                    request.append("Accept: application/sdp\r\n");
                    request.append("\r\n");

                    sp<AMessage> reply = new AMessage('desc', id());
                    mConn->sendRequest(request.c_str(), reply);
                } else {
                    (new AMessage('disc', id()))->post();
                }
                break;
            }

            case 'disc':
            {
                int32_t reconnect;
                if (msg->findInt32("reconnect", &reconnect) && reconnect) {
                    sp<AMessage> reply = new AMessage('conn', id());
                    mConn->connect(mSessionURL.c_str(), reply);
                } else {
                    (new AMessage('quit', id()))->post();
                }
                break;
            }

            case 'desc':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                LOG(INFO) << "DESCRIBE completed with result "
                     << result << " (" << strerror(-result) << ")";

                if (result == OK) {
                    sp<RefBase> obj;
                    CHECK(msg->findObject("response", &obj));
                    sp<ARTSPResponse> response =
                        static_cast<ARTSPResponse *>(obj.get());

                    if (response->mStatusCode == 302) {
                        ssize_t i = response->mHeaders.indexOfKey("location");
                        CHECK_GE(i, 0);

                        mSessionURL = response->mHeaders.valueAt(i);

                        AString request;
                        request = "DESCRIBE ";
                        request.append(mSessionURL);
                        request.append(" RTSP/1.0\r\n");
                        request.append("Accept: application/sdp\r\n");
                        request.append("\r\n");

                        sp<AMessage> reply = new AMessage('desc', id());
                        mConn->sendRequest(request.c_str(), reply);
                        break;
                    }

                    CHECK_EQ(response->mStatusCode, 200u);

                    mSessionDesc = new ASessionDescription;

                    mSessionDesc->setTo(
                            response->mContent->data(),
                            response->mContent->size());

                    CHECK(mSessionDesc->isValid());

                    ssize_t i = response->mHeaders.indexOfKey("content-base");
                    if (i >= 0) {
                        mBaseURL = response->mHeaders.valueAt(i);
                    } else {
                        i = response->mHeaders.indexOfKey("content-location");
                        if (i >= 0) {
                            mBaseURL = response->mHeaders.valueAt(i);
                        } else {
                            mBaseURL = mSessionURL;
                        }
                    }

                    CHECK_GT(mSessionDesc->countTracks(), 1u);
                    setupTrack(1);
                } else {
                    sp<AMessage> reply = new AMessage('disc', id());
                    mConn->disconnect(reply);
                }
                break;
            }

            case 'setu':
            {
                size_t index;
                CHECK(msg->findSize("index", &index));

                TrackInfo *track = NULL;
                size_t trackIndex;
                if (msg->findSize("track-index", &trackIndex)) {
                    track = &mTracks.editItemAt(trackIndex);
                }

                int32_t result;
                CHECK(msg->findInt32("result", &result));

                LOG(INFO) << "SETUP(" << index << ") completed with result "
                     << result << " (" << strerror(-result) << ")";

                if (result != OK) {
                    if (track) {
                        if (!track->mUsingInterleavedTCP) {
                            close(track->mRTPSocket);
                            close(track->mRTCPSocket);
                        }

                        mTracks.removeItemsAt(trackIndex);
                    }
                } else {
                    CHECK(track != NULL);

                    sp<RefBase> obj;
                    CHECK(msg->findObject("response", &obj));
                    sp<ARTSPResponse> response =
                        static_cast<ARTSPResponse *>(obj.get());

                    CHECK_EQ(response->mStatusCode, 200u);

                    ssize_t i = response->mHeaders.indexOfKey("session");
                    CHECK_GE(i, 0);

                    if (index == 1) {
                        mSessionID = response->mHeaders.valueAt(i);
                        i = mSessionID.find(";");
                        if (i >= 0) {
                            // Remove options, i.e. ";timeout=90"
                            mSessionID.erase(i, mSessionID.size() - i);
                        }
                    }

                    sp<AMessage> notify = new AMessage('accu', id());
                    notify->setSize("track-index", trackIndex);

                    mRTPConn->addStream(
                            track->mRTPSocket, track->mRTCPSocket,
                            mSessionDesc, index,
                            notify, track->mUsingInterleavedTCP);

                    mSetupTracksSuccessful = true;
                }

                ++index;
                if (index < mSessionDesc->countTracks()) {
                    setupTrack(index);
                } else if (mSetupTracksSuccessful) {
                    AString request = "PLAY ";
                    request.append(mSessionURL);
                    request.append(" RTSP/1.0\r\n");

                    request.append("Session: ");
                    request.append(mSessionID);
                    request.append("\r\n");

                    request.append("\r\n");

                    sp<AMessage> reply = new AMessage('play', id());
                    mConn->sendRequest(request.c_str(), reply);
                } else {
                    sp<AMessage> reply = new AMessage('disc', id());
                    mConn->disconnect(reply);
                }
                break;
            }

            case 'play':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                LOG(INFO) << "PLAY completed with result "
                     << result << " (" << strerror(-result) << ")";

                if (result == OK) {
                    sp<RefBase> obj;
                    CHECK(msg->findObject("response", &obj));
                    sp<ARTSPResponse> response =
                        static_cast<ARTSPResponse *>(obj.get());

                    CHECK_EQ(response->mStatusCode, 200u);

                    parsePlayResponse(response);

                    sp<AMessage> timeout = new AMessage('tiou', id());
                    timeout->post(kStartupTimeoutUs);
                } else {
                    sp<AMessage> reply = new AMessage('disc', id());
                    mConn->disconnect(reply);
                }

                break;
            }

            case 'abor':
            {
                for (size_t i = 0; i < mTracks.size(); ++i) {
                    TrackInfo *info = &mTracks.editItemAt(i);

                    info->mPacketSource->signalEOS(ERROR_END_OF_STREAM);

                    if (!info->mUsingInterleavedTCP) {
                        mRTPConn->removeStream(info->mRTPSocket, info->mRTCPSocket);

                        close(info->mRTPSocket);
                        close(info->mRTCPSocket);
                    }
                }
                mTracks.clear();

                sp<AMessage> reply = new AMessage('tear', id());

                int32_t reconnect;
                if (msg->findInt32("reconnect", &reconnect) && reconnect) {
                    reply->setInt32("reconnect", true);
                }

                AString request;
                request = "TEARDOWN ";

                // XXX should use aggregate url from SDP here...
                request.append(mSessionURL);
                request.append(" RTSP/1.0\r\n");

                request.append("Session: ");
                request.append(mSessionID);
                request.append("\r\n");

                request.append("\r\n");

                mConn->sendRequest(request.c_str(), reply);
                break;
            }

            case 'tear':
            {
                int32_t result;
                CHECK(msg->findInt32("result", &result));

                LOG(INFO) << "TEARDOWN completed with result "
                     << result << " (" << strerror(-result) << ")";

                sp<AMessage> reply = new AMessage('disc', id());

                int32_t reconnect;
                if (msg->findInt32("reconnect", &reconnect) && reconnect) {
                    reply->setInt32("reconnect", true);
                }

                mConn->disconnect(reply);
                break;
            }

            case 'quit':
            {
                if (mDoneMsg != NULL) {
                    mDoneMsg->setInt32("result", UNKNOWN_ERROR);
                    mDoneMsg->post();
                    mDoneMsg = NULL;
                }
                break;
            }

            case 'chek':
            {
                if (mNumAccessUnitsReceived == 0) {
                    LOG(INFO) << "stream ended? aborting.";
                    (new AMessage('abor', id()))->post();
                    break;
                }

                mNumAccessUnitsReceived = 0;
                msg->post(kAccessUnitTimeoutUs);
                break;
            }

            case 'accu':
            {
                ++mNumAccessUnitsReceived;

                if (!mCheckPending) {
                    mCheckPending = true;
                    sp<AMessage> check = new AMessage('chek', id());
                    check->post(kAccessUnitTimeoutUs);
                }

                size_t trackIndex;
                CHECK(msg->findSize("track-index", &trackIndex));

                if (trackIndex >= mTracks.size()) {
                    LOG(ERROR) << "late packets ignored.";
                    break;
                }

                TrackInfo *track = &mTracks.editItemAt(trackIndex);

                int32_t eos;
                if (msg->findInt32("eos", &eos)) {
                    LOG(INFO) << "received BYE on track index " << trackIndex;
#if 0
                    track->mPacketSource->signalEOS(ERROR_END_OF_STREAM);
#endif
                    return;
                }

                sp<RefBase> obj;
                CHECK(msg->findObject("access-unit", &obj));

                sp<ABuffer> accessUnit = static_cast<ABuffer *>(obj.get());

                uint32_t seqNum = (uint32_t)accessUnit->int32Data();

                if (seqNum < track->mFirstSeqNumInSegment) {
                    LOG(INFO) << "dropping stale access-unit "
                              << "(" << seqNum << " < "
                              << track->mFirstSeqNumInSegment << ")";
                    break;
                }

                uint64_t ntpTime;
                CHECK(accessUnit->meta()->findInt64(
                            "ntp-time", (int64_t *)&ntpTime));

                uint32_t rtpTime;
                CHECK(accessUnit->meta()->findInt32(
                            "rtp-time", (int32_t *)&rtpTime));

                if (track->mNewSegment) {
                    track->mNewSegment = false;

                    LOG(VERBOSE) << "first segment unit ntpTime="
                              << StringPrintf("0x%016llx", ntpTime)
                              << " rtpTime=" << rtpTime
                              << " seq=" << seqNum;
                }

                if (mFirstAccessUnit) {
                    mDoneMsg->setInt32("result", OK);
                    mDoneMsg->post();
                    mDoneMsg = NULL;

                    mFirstAccessUnit = false;
                    mFirstAccessUnitNTP = ntpTime;
                }

                if (ntpTime >= mFirstAccessUnitNTP) {
                    ntpTime -= mFirstAccessUnitNTP;
                } else {
                    ntpTime = 0;
                }

                int64_t timeUs = (int64_t)(ntpTime * 1E6 / (1ll << 32));

                accessUnit->meta()->setInt64("timeUs", timeUs);

#if 0
                int32_t damaged;
                if (accessUnit->meta()->findInt32("damaged", &damaged)
                        && damaged != 0) {
                    LOG(INFO) << "ignoring damaged AU";
                } else
#endif
                {
                    TrackInfo *track = &mTracks.editItemAt(trackIndex);
                    track->mPacketSource->queueAccessUnit(accessUnit);
                }
                break;
            }

            case 'seek':
            {
                if (mSeekPending) {
                    break;
                }

                int64_t timeUs;
                CHECK(msg->findInt64("time", &timeUs));

                mSeekPending = true;

                AString request = "PAUSE ";
                request.append(mSessionURL);
                request.append(" RTSP/1.0\r\n");

                request.append("Session: ");
                request.append(mSessionID);
                request.append("\r\n");

                request.append("\r\n");

                sp<AMessage> reply = new AMessage('see1', id());
                reply->setInt64("time", timeUs);
                mConn->sendRequest(request.c_str(), reply);
                break;
            }

            case 'see1':
            {
                // Session is paused now.
                for (size_t i = 0; i < mTracks.size(); ++i) {
                    mTracks.editItemAt(i).mPacketSource->flushQueue();
                }

                int64_t timeUs;
                CHECK(msg->findInt64("time", &timeUs));

                AString request = "PLAY ";
                request.append(mSessionURL);
                request.append(" RTSP/1.0\r\n");

                request.append("Session: ");
                request.append(mSessionID);
                request.append("\r\n");

                request.append(
                        StringPrintf(
                            "Range: npt=%lld-\r\n", timeUs / 1000000ll));

                request.append("\r\n");

                sp<AMessage> reply = new AMessage('see2', id());
                mConn->sendRequest(request.c_str(), reply);
                break;
            }

            case 'see2':
            {
                CHECK(mSeekPending);

                int32_t result;
                CHECK(msg->findInt32("result", &result));

                LOG(INFO) << "PLAY completed with result "
                     << result << " (" << strerror(-result) << ")";

                CHECK_EQ(result, (status_t)OK);

                sp<RefBase> obj;
                CHECK(msg->findObject("response", &obj));
                sp<ARTSPResponse> response =
                    static_cast<ARTSPResponse *>(obj.get());

                CHECK_EQ(response->mStatusCode, 200u);

                parsePlayResponse(response);

                LOG(INFO) << "seek completed.";
                mSeekPending = false;
                break;
            }

            case 'biny':
            {
                sp<RefBase> obj;
                CHECK(msg->findObject("buffer", &obj));
                sp<ABuffer> buffer = static_cast<ABuffer *>(obj.get());

                int32_t index;
                CHECK(buffer->meta()->findInt32("index", &index));

                mRTPConn->injectPacket(index, buffer);
                break;
            }

            case 'tiou':
            {
                if (mFirstAccessUnit) {
                    if (mTryTCPInterleaving) {
                        LOG(WARNING) << "Never received any data, disconnecting.";
                        (new AMessage('abor', id()))->post();
                    } else {
                        LOG(WARNING)
                            << "Never received any data, switching transports.";

                        mTryTCPInterleaving = true;

                        sp<AMessage> msg = new AMessage('abor', id());
                        msg->setInt32("reconnect", true);
                        msg->post();
                    }
                }
                break;
            }

            default:
                TRESPASS();
                break;
        }
    }

    static void SplitString(
            const AString &s, const char *separator, List<AString> *items) {
        items->clear();
        size_t start = 0;
        while (start < s.size()) {
            ssize_t offset = s.find(separator, start);

            if (offset < 0) {
                items->push_back(AString(s, start, s.size() - start));
                break;
            }

            items->push_back(AString(s, start, offset - start));
            start = offset + strlen(separator);
        }
    }

    void parsePlayResponse(const sp<ARTSPResponse> &response) {
        ssize_t i = response->mHeaders.indexOfKey("range");
        if (i < 0) {
            // Server doesn't even tell use what range it is going to
            // play, therefore we won't support seeking.
            return;
        }

        AString range = response->mHeaders.valueAt(i);
        LOG(VERBOSE) << "Range: " << range;

        AString val;
        CHECK(GetAttribute(range.c_str(), "npt", &val));
        float npt1, npt2;

        if (val == "now-") {
            // This is a live stream and therefore not seekable.
            return;
        } else {
            CHECK_EQ(sscanf(val.c_str(), "%f-%f", &npt1, &npt2), 2);
        }

        i = response->mHeaders.indexOfKey("rtp-info");
        CHECK_GE(i, 0);

        AString rtpInfo = response->mHeaders.valueAt(i);
        List<AString> streamInfos;
        SplitString(rtpInfo, ",", &streamInfos);

        int n = 1;
        for (List<AString>::iterator it = streamInfos.begin();
             it != streamInfos.end(); ++it) {
            (*it).trim();
            LOG(VERBOSE) << "streamInfo[" << n << "] = " << *it;

            CHECK(GetAttribute((*it).c_str(), "url", &val));

            size_t trackIndex = 0;
            while (trackIndex < mTracks.size()
                    && !(val == mTracks.editItemAt(trackIndex).mURL)) {
                ++trackIndex;
            }
            CHECK_LT(trackIndex, mTracks.size());

            CHECK(GetAttribute((*it).c_str(), "seq", &val));

            char *end;
            unsigned long seq = strtoul(val.c_str(), &end, 10);

            TrackInfo *info = &mTracks.editItemAt(trackIndex);
            info->mFirstSeqNumInSegment = seq;
            info->mNewSegment = true;

            CHECK(GetAttribute((*it).c_str(), "rtptime", &val));

            uint32_t rtpTime = strtoul(val.c_str(), &end, 10);

            LOG(VERBOSE) << "track #" << n
                      << ": rtpTime=" << rtpTime << " <=> npt=" << npt1;

            info->mPacketSource->setNormalPlayTimeMapping(
                    rtpTime, (int64_t)(npt1 * 1E6));

            ++n;
        }
    }

    sp<APacketSource> getPacketSource(size_t index) {
        CHECK_GE(index, 0u);
        CHECK_LT(index, mTracks.size());

        return mTracks.editItemAt(index).mPacketSource;
    }

    size_t countTracks() const {
        return mTracks.size();
    }

private:
    sp<ALooper> mLooper;
    sp<ALooper> mNetLooper;
    sp<ARTSPConnection> mConn;
    sp<ARTPConnection> mRTPConn;
    sp<ASessionDescription> mSessionDesc;
    AString mSessionURL;
    AString mBaseURL;
    AString mSessionID;
    bool mSetupTracksSuccessful;
    bool mSeekPending;
    bool mFirstAccessUnit;
    uint64_t mFirstAccessUnitNTP;
    int64_t mNumAccessUnitsReceived;
    bool mCheckPending;
    bool mTryTCPInterleaving;

    struct TrackInfo {
        AString mURL;
        int mRTPSocket;
        int mRTCPSocket;
        bool mUsingInterleavedTCP;
        uint32_t mFirstSeqNumInSegment;
        bool mNewSegment;

        sp<APacketSource> mPacketSource;
    };
    Vector<TrackInfo> mTracks;

    sp<AMessage> mDoneMsg;

    void setupTrack(size_t index) {
        sp<APacketSource> source =
            new APacketSource(mSessionDesc, index);

        if (source->initCheck() != OK) {
            LOG(WARNING) << "Unsupported format. Ignoring track #"
                         << index << ".";

            sp<AMessage> reply = new AMessage('setu', id());
            reply->setSize("index", index);
            reply->setInt32("result", ERROR_UNSUPPORTED);
            reply->post();
            return;
        }

        AString url;
        CHECK(mSessionDesc->findAttribute(index, "a=control", &url));

        AString trackURL;
        CHECK(MakeURL(mBaseURL.c_str(), url.c_str(), &trackURL));

        mTracks.push(TrackInfo());
        TrackInfo *info = &mTracks.editItemAt(mTracks.size() - 1);
        info->mURL = trackURL;
        info->mPacketSource = source;
        info->mUsingInterleavedTCP = false;
        info->mFirstSeqNumInSegment = 0;
        info->mNewSegment = true;

        LOG(VERBOSE) << "track #" << mTracks.size() << " URL=" << trackURL;

        AString request = "SETUP ";
        request.append(trackURL);
        request.append(" RTSP/1.0\r\n");

        if (mTryTCPInterleaving) {
            size_t interleaveIndex = 2 * (mTracks.size() - 1);
            info->mUsingInterleavedTCP = true;
            info->mRTPSocket = interleaveIndex;
            info->mRTCPSocket = interleaveIndex + 1;

            request.append("Transport: RTP/AVP/TCP;interleaved=");
            request.append(interleaveIndex);
            request.append("-");
            request.append(interleaveIndex + 1);
        } else {
            unsigned rtpPort;
            ARTPConnection::MakePortPair(
                    &info->mRTPSocket, &info->mRTCPSocket, &rtpPort);

            request.append("Transport: RTP/AVP/UDP;unicast;client_port=");
            request.append(rtpPort);
            request.append("-");
            request.append(rtpPort + 1);
        }

        request.append("\r\n");

        if (index > 1) {
            request.append("Session: ");
            request.append(mSessionID);
            request.append("\r\n");
        }

        request.append("\r\n");

        sp<AMessage> reply = new AMessage('setu', id());
        reply->setSize("index", index);
        reply->setSize("track-index", mTracks.size() - 1);
        mConn->sendRequest(request.c_str(), reply);
    }

    static bool MakeURL(const char *baseURL, const char *url, AString *out) {
        out->clear();

        if (strncasecmp("rtsp://", baseURL, 7)) {
            // Base URL must be absolute
            return false;
        }

        if (!strncasecmp("rtsp://", url, 7)) {
            // "url" is already an absolute URL, ignore base URL.
            out->setTo(url);
            return true;
        }

        size_t n = strlen(baseURL);
        if (baseURL[n - 1] == '/') {
            out->setTo(baseURL);
            out->append(url);
        } else {
            const char *slashPos = strrchr(baseURL, '/');

            if (slashPos > &baseURL[6]) {
                out->setTo(baseURL, slashPos - baseURL);
            } else {
                out->setTo(baseURL);
            }

            out->append("/");
            out->append(url);
        }

        return true;
    }

    DISALLOW_EVIL_CONSTRUCTORS(MyHandler);
};

}  // namespace android

#endif  // MY_HANDLER_H_
