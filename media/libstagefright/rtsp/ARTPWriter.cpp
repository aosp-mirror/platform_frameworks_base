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
#define LOG_TAG "ARTPWriter"
#include <utils/Log.h>

#include "ARTPWriter.h"

#include <fcntl.h>

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaSource.h>
#include <media/stagefright/MetaData.h>
#include <utils/ByteOrder.h>

#define PT      97
#define PT_STR  "97"

namespace android {

// static const size_t kMaxPacketSize = 65507;  // maximum payload in UDP over IP
static const size_t kMaxPacketSize = 1500;

static int UniformRand(int limit) {
    return ((double)rand() * limit) / RAND_MAX;
}

ARTPWriter::ARTPWriter(int fd)
    : mFlags(0),
      mFd(dup(fd)),
      mLooper(new ALooper),
      mReflector(new AHandlerReflector<ARTPWriter>(this)) {
    CHECK_GE(fd, 0);

    mLooper->setName("rtp writer");
    mLooper->registerHandler(mReflector);
    mLooper->start();

    mSocket = socket(AF_INET, SOCK_DGRAM, 0);
    CHECK_GE(mSocket, 0);

    memset(mRTPAddr.sin_zero, 0, sizeof(mRTPAddr.sin_zero));
    mRTPAddr.sin_family = AF_INET;

#if 1
    mRTPAddr.sin_addr.s_addr = INADDR_ANY;
#else
    mRTPAddr.sin_addr.s_addr = inet_addr("172.19.18.246");
#endif

    mRTPAddr.sin_port = htons(5634);
    CHECK_EQ(0, ntohs(mRTPAddr.sin_port) & 1);

    mRTCPAddr = mRTPAddr;
    mRTCPAddr.sin_port = htons(ntohs(mRTPAddr.sin_port) | 1);

#if LOG_TO_FILES
    mRTPFd = open(
            "/data/misc/rtpout.bin",
            O_WRONLY | O_CREAT | O_TRUNC,
            0644);
    CHECK_GE(mRTPFd, 0);

    mRTCPFd = open(
            "/data/misc/rtcpout.bin",
            O_WRONLY | O_CREAT | O_TRUNC,
            0644);
    CHECK_GE(mRTCPFd, 0);
#endif
}

ARTPWriter::~ARTPWriter() {
#if LOG_TO_FILES
    close(mRTCPFd);
    mRTCPFd = -1;

    close(mRTPFd);
    mRTPFd = -1;
#endif

    close(mSocket);
    mSocket = -1;

    close(mFd);
    mFd = -1;
}

status_t ARTPWriter::addSource(const sp<MediaSource> &source) {
    mSource = source;
    return OK;
}

bool ARTPWriter::reachedEOS() {
    Mutex::Autolock autoLock(mLock);
    return (mFlags & kFlagEOS) != 0;
}

status_t ARTPWriter::start(MetaData *params) {
    Mutex::Autolock autoLock(mLock);
    if (mFlags & kFlagStarted) {
        return INVALID_OPERATION;
    }

    mFlags &= ~kFlagEOS;
    mSourceID = rand();
    mSeqNo = UniformRand(65536);
    mRTPTimeBase = rand();
    mNumRTPSent = 0;
    mNumRTPOctetsSent = 0;
    mLastRTPTime = 0;
    mLastNTPTime = 0;
    mNumSRsSent = 0;

    const char *mime;
    CHECK(mSource->getFormat()->findCString(kKeyMIMEType, &mime));

    mMode = INVALID;
    if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_AVC)) {
        mMode = H264;
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_VIDEO_H263)) {
        mMode = H263;
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AMR_NB)) {
        mMode = AMR_NB;
    } else if (!strcasecmp(mime, MEDIA_MIMETYPE_AUDIO_AMR_WB)) {
        mMode = AMR_WB;
    } else {
        TRESPASS();
    }

    (new AMessage(kWhatStart, mReflector->id()))->post();

    while (!(mFlags & kFlagStarted)) {
        mCondition.wait(mLock);
    }

    return OK;
}

status_t ARTPWriter::stop() {
    Mutex::Autolock autoLock(mLock);
    if (!(mFlags & kFlagStarted)) {
        return OK;
    }

    (new AMessage(kWhatStop, mReflector->id()))->post();

    while (mFlags & kFlagStarted) {
        mCondition.wait(mLock);
    }
    return OK;
}

status_t ARTPWriter::pause() {
    return OK;
}

static void StripStartcode(MediaBuffer *buffer) {
    if (buffer->range_length() < 4) {
        return;
    }

    const uint8_t *ptr =
        (const uint8_t *)buffer->data() + buffer->range_offset();

    if (!memcmp(ptr, "\x00\x00\x00\x01", 4)) {
        buffer->set_range(
                buffer->range_offset() + 4, buffer->range_length() - 4);
    }
}

void ARTPWriter::onMessageReceived(const sp<AMessage> &msg) {
    switch (msg->what()) {
        case kWhatStart:
        {
            CHECK_EQ(mSource->start(), (status_t)OK);

#if 0
            if (mMode == H264) {
                MediaBuffer *buffer;
                CHECK_EQ(mSource->read(&buffer), (status_t)OK);

                StripStartcode(buffer);
                makeH264SPropParamSets(buffer);
                buffer->release();
                buffer = NULL;
            }

            dumpSessionDesc();
#endif

            {
                Mutex::Autolock autoLock(mLock);
                mFlags |= kFlagStarted;
                mCondition.signal();
            }

            (new AMessage(kWhatRead, mReflector->id()))->post();
            (new AMessage(kWhatSendSR, mReflector->id()))->post();
            break;
        }

        case kWhatStop:
        {
            CHECK_EQ(mSource->stop(), (status_t)OK);

            sendBye();

            {
                Mutex::Autolock autoLock(mLock);
                mFlags &= ~kFlagStarted;
                mCondition.signal();
            }
            break;
        }

        case kWhatRead:
        {
            {
                Mutex::Autolock autoLock(mLock);
                if (!(mFlags & kFlagStarted)) {
                    break;
                }
            }

            onRead(msg);
            break;
        }

        case kWhatSendSR:
        {
            {
                Mutex::Autolock autoLock(mLock);
                if (!(mFlags & kFlagStarted)) {
                    break;
                }
            }

            onSendSR(msg);
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

void ARTPWriter::onRead(const sp<AMessage> &msg) {
    MediaBuffer *mediaBuf;
    status_t err = mSource->read(&mediaBuf);

    if (err != OK) {
        LOGI("reached EOS.");

        Mutex::Autolock autoLock(mLock);
        mFlags |= kFlagEOS;
        return;
    }

    if (mediaBuf->range_length() > 0) {
        ALOGV("read buffer of size %d", mediaBuf->range_length());

        if (mMode == H264) {
            StripStartcode(mediaBuf);
            sendAVCData(mediaBuf);
        } else if (mMode == H263) {
            sendH263Data(mediaBuf);
        } else if (mMode == AMR_NB || mMode == AMR_WB) {
            sendAMRData(mediaBuf);
        }
    }

    mediaBuf->release();
    mediaBuf = NULL;

    msg->post();
}

void ARTPWriter::onSendSR(const sp<AMessage> &msg) {
    sp<ABuffer> buffer = new ABuffer(65536);
    buffer->setRange(0, 0);

    addSR(buffer);
    addSDES(buffer);

    send(buffer, true /* isRTCP */);

    ++mNumSRsSent;
    msg->post(3000000);
}

void ARTPWriter::send(const sp<ABuffer> &buffer, bool isRTCP) {
    ssize_t n = sendto(
            mSocket, buffer->data(), buffer->size(), 0,
            (const struct sockaddr *)(isRTCP ? &mRTCPAddr : &mRTPAddr),
            sizeof(mRTCPAddr));

    CHECK_EQ(n, (ssize_t)buffer->size());

#if LOG_TO_FILES
    int fd = isRTCP ? mRTCPFd : mRTPFd;

    uint32_t ms = tolel(ALooper::GetNowUs() / 1000ll);
    uint32_t length = tolel(buffer->size());
    write(fd, &ms, sizeof(ms));
    write(fd, &length, sizeof(length));
    write(fd, buffer->data(), buffer->size());
#endif
}

void ARTPWriter::addSR(const sp<ABuffer> &buffer) {
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

void ARTPWriter::addSDES(const sp<ABuffer> &buffer) {
    uint8_t *data = buffer->data() + buffer->size();
    data[0] = 0x80 | 1;
    data[1] = 202;  // SDES
    data[4] = mSourceID >> 24;
    data[5] = (mSourceID >> 16) & 0xff;
    data[6] = (mSourceID >> 8) & 0xff;
    data[7] = mSourceID & 0xff;

    size_t offset = 8;

    data[offset++] = 1;  // CNAME

    static const char *kCNAME = "someone@somewhere";
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

// static
uint64_t ARTPWriter::GetNowNTP() {
    uint64_t nowUs = ALooper::GetNowUs();

    nowUs += ((70ll * 365 + 17) * 24) * 60 * 60 * 1000000ll;

    uint64_t hi = nowUs / 1000000ll;
    uint64_t lo = ((1ll << 32) * (nowUs % 1000000ll)) / 1000000ll;

    return (hi << 32) | lo;
}

void ARTPWriter::dumpSessionDesc() {
    AString sdp;
    sdp = "v=0\r\n";

    sdp.append("o=- ");

    uint64_t ntp = GetNowNTP();
    sdp.append(ntp);
    sdp.append(" ");
    sdp.append(ntp);
    sdp.append(" IN IP4 127.0.0.0\r\n");

    sdp.append(
          "s=Sample\r\n"
          "i=Playing around\r\n"
          "c=IN IP4 ");

    struct in_addr addr;
    addr.s_addr = ntohl(INADDR_LOOPBACK);

    sdp.append(inet_ntoa(addr));

    sdp.append(
          "\r\n"
          "t=0 0\r\n"
          "a=range:npt=now-\r\n");

    sp<MetaData> meta = mSource->getFormat();

    if (mMode == H264 || mMode == H263) {
        sdp.append("m=video ");
    } else {
        sdp.append("m=audio ");
    }

    sdp.append(StringPrintf("%d", ntohs(mRTPAddr.sin_port)));
    sdp.append(
          " RTP/AVP " PT_STR "\r\n"
          "b=AS 320000\r\n"
          "a=rtpmap:" PT_STR " ");

    if (mMode == H264) {
        sdp.append("H264/90000");
    } else if (mMode == H263) {
        sdp.append("H263-1998/90000");
    } else if (mMode == AMR_NB || mMode == AMR_WB) {
        int32_t sampleRate, numChannels;
        CHECK(mSource->getFormat()->findInt32(kKeySampleRate, &sampleRate));
        CHECK(mSource->getFormat()->findInt32(kKeyChannelCount, &numChannels));

        CHECK_EQ(numChannels, 1);
        CHECK_EQ(sampleRate, (mMode == AMR_NB) ? 8000 : 16000);

        sdp.append(mMode == AMR_NB ? "AMR" : "AMR-WB");
        sdp.append(StringPrintf("/%d/%d", sampleRate, numChannels));
    } else {
        TRESPASS();
    }

    sdp.append("\r\n");

    if (mMode == H264 || mMode == H263) {
        int32_t width, height;
        CHECK(meta->findInt32(kKeyWidth, &width));
        CHECK(meta->findInt32(kKeyHeight, &height));

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
    }

    if (mMode == H264) {
        sdp.append(
              "a=fmtp:" PT_STR " profile-level-id=");
        sdp.append(mProfileLevel);
        sdp.append(";sprop-parameter-sets=");

        sdp.append(mSeqParamSet);
        sdp.append(",");
        sdp.append(mPicParamSet);
        sdp.append(";packetization-mode=1\r\n");
    } else if (mMode == AMR_NB || mMode == AMR_WB) {
        sdp.append("a=fmtp:" PT_STR " octed-align\r\n");
    }

    LOGI("%s", sdp.c_str());
}

void ARTPWriter::makeH264SPropParamSets(MediaBuffer *buffer) {
    static const char kStartCode[] = "\x00\x00\x00\x01";

    const uint8_t *data =
        (const uint8_t *)buffer->data() + buffer->range_offset();
    size_t size = buffer->range_length();

    CHECK_GE(size, 0u);

    size_t startCodePos = 0;
    while (startCodePos + 3 < size
            && memcmp(kStartCode, &data[startCodePos], 4)) {
        ++startCodePos;
    }

    CHECK_LT(startCodePos + 3, size);

    CHECK_EQ((unsigned)data[0], 0x67u);

    mProfileLevel =
        StringPrintf("%02X%02X%02X", data[1], data[2], data[3]);

    encodeBase64(data, startCodePos, &mSeqParamSet);

    encodeBase64(&data[startCodePos + 4], size - startCodePos - 4,
                 &mPicParamSet);
}

void ARTPWriter::sendBye() {
    sp<ABuffer> buffer = new ABuffer(8);
    uint8_t *data = buffer->data();
    *data++ = (2 << 6) | 1;
    *data++ = 203;
    *data++ = 0;
    *data++ = 1;
    *data++ = mSourceID >> 24;
    *data++ = (mSourceID >> 16) & 0xff;
    *data++ = (mSourceID >> 8) & 0xff;
    *data++ = mSourceID & 0xff;
    buffer->setRange(0, 8);

    send(buffer, true /* isRTCP */);
}

void ARTPWriter::sendAVCData(MediaBuffer *mediaBuf) {
    // 12 bytes RTP header + 2 bytes for the FU-indicator and FU-header.
    CHECK_GE(kMaxPacketSize, 12u + 2u);

    int64_t timeUs;
    CHECK(mediaBuf->meta_data()->findInt64(kKeyTime, &timeUs));

    uint32_t rtpTime = mRTPTimeBase + (timeUs * 9 / 100ll);

    const uint8_t *mediaData =
        (const uint8_t *)mediaBuf->data() + mediaBuf->range_offset();

    sp<ABuffer> buffer = new ABuffer(kMaxPacketSize);
    if (mediaBuf->range_length() + 12 <= buffer->capacity()) {
        // The data fits into a single packet
        uint8_t *data = buffer->data();
        data[0] = 0x80;
        data[1] = (1 << 7) | PT;  // M-bit
        data[2] = (mSeqNo >> 8) & 0xff;
        data[3] = mSeqNo & 0xff;
        data[4] = rtpTime >> 24;
        data[5] = (rtpTime >> 16) & 0xff;
        data[6] = (rtpTime >> 8) & 0xff;
        data[7] = rtpTime & 0xff;
        data[8] = mSourceID >> 24;
        data[9] = (mSourceID >> 16) & 0xff;
        data[10] = (mSourceID >> 8) & 0xff;
        data[11] = mSourceID & 0xff;

        memcpy(&data[12],
               mediaData, mediaBuf->range_length());

        buffer->setRange(0, mediaBuf->range_length() + 12);

        send(buffer, false /* isRTCP */);

        ++mSeqNo;
        ++mNumRTPSent;
        mNumRTPOctetsSent += buffer->size() - 12;
    } else {
        // FU-A

        unsigned nalType = mediaData[0];
        size_t offset = 1;

        bool firstPacket = true;
        while (offset < mediaBuf->range_length()) {
            size_t size = mediaBuf->range_length() - offset;
            bool lastPacket = true;
            if (size + 12 + 2 > buffer->capacity()) {
                lastPacket = false;
                size = buffer->capacity() - 12 - 2;
            }

            uint8_t *data = buffer->data();
            data[0] = 0x80;
            data[1] = (lastPacket ? (1 << 7) : 0x00) | PT;  // M-bit
            data[2] = (mSeqNo >> 8) & 0xff;
            data[3] = mSeqNo & 0xff;
            data[4] = rtpTime >> 24;
            data[5] = (rtpTime >> 16) & 0xff;
            data[6] = (rtpTime >> 8) & 0xff;
            data[7] = rtpTime & 0xff;
            data[8] = mSourceID >> 24;
            data[9] = (mSourceID >> 16) & 0xff;
            data[10] = (mSourceID >> 8) & 0xff;
            data[11] = mSourceID & 0xff;

            data[12] = 28 | (nalType & 0xe0);

            CHECK(!firstPacket || !lastPacket);

            data[13] =
                (firstPacket ? 0x80 : 0x00)
                | (lastPacket ? 0x40 : 0x00)
                | (nalType & 0x1f);

            memcpy(&data[14], &mediaData[offset], size);

            buffer->setRange(0, 14 + size);

            send(buffer, false /* isRTCP */);

            ++mSeqNo;
            ++mNumRTPSent;
            mNumRTPOctetsSent += buffer->size() - 12;

            firstPacket = false;
            offset += size;
        }
    }

    mLastRTPTime = rtpTime;
    mLastNTPTime = GetNowNTP();
}

void ARTPWriter::sendH263Data(MediaBuffer *mediaBuf) {
    CHECK_GE(kMaxPacketSize, 12u + 2u);

    int64_t timeUs;
    CHECK(mediaBuf->meta_data()->findInt64(kKeyTime, &timeUs));

    uint32_t rtpTime = mRTPTimeBase + (timeUs * 9 / 100ll);

    const uint8_t *mediaData =
        (const uint8_t *)mediaBuf->data() + mediaBuf->range_offset();

    // hexdump(mediaData, mediaBuf->range_length());

    CHECK_EQ((unsigned)mediaData[0], 0u);
    CHECK_EQ((unsigned)mediaData[1], 0u);

    size_t offset = 2;
    size_t size = mediaBuf->range_length();

    while (offset < size) {
        sp<ABuffer> buffer = new ABuffer(kMaxPacketSize);
        // CHECK_LE(mediaBuf->range_length() -2 + 14, buffer->capacity());

        size_t remaining = size - offset;
        bool lastPacket = (remaining + 14 <= buffer->capacity());
        if (!lastPacket) {
            remaining = buffer->capacity() - 14;
        }

        uint8_t *data = buffer->data();
        data[0] = 0x80;
        data[1] = (lastPacket ? 0x80 : 0x00) | PT;  // M-bit
        data[2] = (mSeqNo >> 8) & 0xff;
        data[3] = mSeqNo & 0xff;
        data[4] = rtpTime >> 24;
        data[5] = (rtpTime >> 16) & 0xff;
        data[6] = (rtpTime >> 8) & 0xff;
        data[7] = rtpTime & 0xff;
        data[8] = mSourceID >> 24;
        data[9] = (mSourceID >> 16) & 0xff;
        data[10] = (mSourceID >> 8) & 0xff;
        data[11] = mSourceID & 0xff;

        data[12] = (offset == 2) ? 0x04 : 0x00;  // P=?, V=0
        data[13] = 0x00;  // PLEN = PEBIT = 0

        memcpy(&data[14], &mediaData[offset], remaining);
        offset += remaining;

        buffer->setRange(0, remaining + 14);

        send(buffer, false /* isRTCP */);

        ++mSeqNo;
        ++mNumRTPSent;
        mNumRTPOctetsSent += buffer->size() - 12;
    }

    mLastRTPTime = rtpTime;
    mLastNTPTime = GetNowNTP();
}

static size_t getFrameSize(bool isWide, unsigned FT) {
    static const size_t kFrameSizeNB[8] = {
        95, 103, 118, 134, 148, 159, 204, 244
    };
    static const size_t kFrameSizeWB[9] = {
        132, 177, 253, 285, 317, 365, 397, 461, 477
    };

    size_t frameSize = isWide ? kFrameSizeWB[FT] : kFrameSizeNB[FT];

    // Round up bits to bytes and add 1 for the header byte.
    frameSize = (frameSize + 7) / 8 + 1;

    return frameSize;
}

void ARTPWriter::sendAMRData(MediaBuffer *mediaBuf) {
    const uint8_t *mediaData =
        (const uint8_t *)mediaBuf->data() + mediaBuf->range_offset();

    size_t mediaLength = mediaBuf->range_length();

    CHECK_GE(kMaxPacketSize, 12u + 1u + mediaLength);

    const bool isWide = (mMode == AMR_WB);

    int64_t timeUs;
    CHECK(mediaBuf->meta_data()->findInt64(kKeyTime, &timeUs));
    uint32_t rtpTime = mRTPTimeBase + (timeUs / (isWide ? 250 : 125));

    // hexdump(mediaData, mediaLength);

    Vector<uint8_t> tableOfContents;
    size_t srcOffset = 0;
    while (srcOffset < mediaLength) {
        uint8_t toc = mediaData[srcOffset];

        unsigned FT = (toc >> 3) & 0x0f;
        CHECK((isWide && FT <= 8) || (!isWide && FT <= 7));

        tableOfContents.push(toc);
        srcOffset += getFrameSize(isWide, FT);
    }
    CHECK_EQ(srcOffset, mediaLength);

    sp<ABuffer> buffer = new ABuffer(kMaxPacketSize);
    CHECK_LE(mediaLength + 12 + 1, buffer->capacity());

    // The data fits into a single packet
    uint8_t *data = buffer->data();
    data[0] = 0x80;
    data[1] = PT;
    if (mNumRTPSent == 0) {
        // Signal start of talk-spurt.
        data[1] |= 0x80;  // M-bit
    }
    data[2] = (mSeqNo >> 8) & 0xff;
    data[3] = mSeqNo & 0xff;
    data[4] = rtpTime >> 24;
    data[5] = (rtpTime >> 16) & 0xff;
    data[6] = (rtpTime >> 8) & 0xff;
    data[7] = rtpTime & 0xff;
    data[8] = mSourceID >> 24;
    data[9] = (mSourceID >> 16) & 0xff;
    data[10] = (mSourceID >> 8) & 0xff;
    data[11] = mSourceID & 0xff;

    data[12] = 0xf0;  // CMR=15, RR=0

    size_t dstOffset = 13;

    for (size_t i = 0; i < tableOfContents.size(); ++i) {
        uint8_t toc = tableOfContents[i];

        if (i + 1 < tableOfContents.size()) {
            toc |= 0x80;
        } else {
            toc &= ~0x80;
        }

        data[dstOffset++] = toc;
    }

    srcOffset = 0;
    for (size_t i = 0; i < tableOfContents.size(); ++i) {
        uint8_t toc = tableOfContents[i];
        unsigned FT = (toc >> 3) & 0x0f;
        size_t frameSize = getFrameSize(isWide, FT);

        ++srcOffset;  // skip toc
        memcpy(&data[dstOffset], &mediaData[srcOffset], frameSize - 1);
        srcOffset += frameSize - 1;
        dstOffset += frameSize - 1;
    }

    buffer->setRange(0, dstOffset);

    send(buffer, false /* isRTCP */);

    ++mSeqNo;
    ++mNumRTPSent;
    mNumRTPOctetsSent += buffer->size() - 12;

    mLastRTPTime = rtpTime;
    mLastNTPTime = GetNowNTP();
}

}  // namespace android

