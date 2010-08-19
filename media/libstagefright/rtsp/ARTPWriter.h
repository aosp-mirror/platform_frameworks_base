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

#ifndef A_RTP_WRITER_H_

#define A_RTP_WRITER_H_

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/AHandlerReflector.h>
#include <media/stagefright/foundation/AString.h>
#include <media/stagefright/foundation/base64.h>
#include <media/stagefright/MediaWriter.h>

#include <arpa/inet.h>
#include <sys/socket.h>

#define LOG_TO_FILES    0

namespace android {

struct ABuffer;
struct MediaBuffer;

struct ARTPWriter : public MediaWriter {
    ARTPWriter(int fd);

    virtual status_t addSource(const sp<MediaSource> &source);
    virtual bool reachedEOS();
    virtual status_t start(MetaData *params);
    virtual status_t stop();
    virtual status_t pause();

    virtual void onMessageReceived(const sp<AMessage> &msg);

protected:
    virtual ~ARTPWriter();

private:
    enum {
        kWhatStart  = 'strt',
        kWhatStop   = 'stop',
        kWhatRead   = 'read',
        kWhatSendSR = 'sr  ',
    };

    enum {
        kFlagStarted  = 1,
        kFlagEOS      = 2,
    };

    Mutex mLock;
    Condition mCondition;
    uint32_t mFlags;

    int mFd;

#if LOG_TO_FILES
    int mRTPFd;
    int mRTCPFd;
#endif

    sp<MediaSource> mSource;
    sp<ALooper> mLooper;
    sp<AHandlerReflector<ARTPWriter> > mReflector;

    int mSocket;
    struct sockaddr_in mRTPAddr;
    struct sockaddr_in mRTCPAddr;

    AString mProfileLevel;
    AString mSeqParamSet;
    AString mPicParamSet;

    uint32_t mSourceID;
    uint32_t mSeqNo;
    uint32_t mRTPTimeBase;
    uint32_t mNumRTPSent;
    uint32_t mNumRTPOctetsSent;
    uint32_t mLastRTPTime;
    uint64_t mLastNTPTime;

    int32_t mNumSRsSent;

    enum {
        INVALID,
        H264,
        H263,
        AMR_NB,
        AMR_WB,
    } mMode;

    static uint64_t GetNowNTP();

    void onRead(const sp<AMessage> &msg);
    void onSendSR(const sp<AMessage> &msg);

    void addSR(const sp<ABuffer> &buffer);
    void addSDES(const sp<ABuffer> &buffer);

    void makeH264SPropParamSets(MediaBuffer *buffer);
    void dumpSessionDesc();

    void sendBye();
    void sendAVCData(MediaBuffer *mediaBuf);
    void sendH263Data(MediaBuffer *mediaBuf);
    void sendAMRData(MediaBuffer *mediaBuf);

    void send(const sp<ABuffer> &buffer, bool isRTCP);

    DISALLOW_EVIL_CONSTRUCTORS(ARTPWriter);
};

}  // namespace android

#endif  // A_RTP_WRITER_H_
