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
#define LOG_TAG "ARTPSource"
#include <utils/Log.h>

#include "ARTPSource.h"

#include "AAMRAssembler.h"
#include "AAVCAssembler.h"
#include "AH263Assembler.h"
#include "AMPEG4AudioAssembler.h"
#include "AMPEG4ElementaryAssembler.h"
#include "ARawAudioAssembler.h"
#include "ASessionDescription.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>

namespace android {

static const uint32_t kSourceID = 0xdeadbeef;

ARTPSource::ARTPSource(
        uint32_t id,
        const sp<ASessionDescription> &sessionDesc, size_t index,
        const sp<AMessage> &notify)
    : mID(id),
      mHighestSeqNumber(0),
      mNumBuffersReceived(0),
      mLastNTPTime(0),
      mLastNTPTimeUpdateUs(0),
      mIssueFIRRequests(false),
      mLastFIRRequestUs(-1),
      mNextFIRSeqNo((rand() * 256.0) / RAND_MAX),
      mNotify(notify) {
    unsigned long PT;
    AString desc;
    AString params;
    sessionDesc->getFormatType(index, &PT, &desc, &params);

    if (!strncmp(desc.c_str(), "H264/", 5)) {
        mAssembler = new AAVCAssembler(notify);
        mIssueFIRRequests = true;
    } else if (!strncmp(desc.c_str(), "MP4A-LATM/", 10)) {
        mAssembler = new AMPEG4AudioAssembler(notify, params);
    } else if (!strncmp(desc.c_str(), "H263-1998/", 10)
            || !strncmp(desc.c_str(), "H263-2000/", 10)) {
        mAssembler = new AH263Assembler(notify);
        mIssueFIRRequests = true;
    } else if (!strncmp(desc.c_str(), "AMR/", 4)) {
        mAssembler = new AAMRAssembler(notify, false /* isWide */, params);
    } else  if (!strncmp(desc.c_str(), "AMR-WB/", 7)) {
        mAssembler = new AAMRAssembler(notify, true /* isWide */, params);
    } else if (!strncmp(desc.c_str(), "MP4V-ES/", 8)
            || !strncasecmp(desc.c_str(), "mpeg4-generic/", 14)) {
        mAssembler = new AMPEG4ElementaryAssembler(notify, desc, params);
        mIssueFIRRequests = true;
    } else if (ARawAudioAssembler::Supports(desc.c_str())) {
        mAssembler = new ARawAudioAssembler(notify, desc.c_str(), params);
    } else {
        TRESPASS();
    }
}

static uint32_t AbsDiff(uint32_t seq1, uint32_t seq2) {
    return seq1 > seq2 ? seq1 - seq2 : seq2 - seq1;
}

void ARTPSource::processRTPPacket(const sp<ABuffer> &buffer) {
    if (queuePacket(buffer) && mAssembler != NULL) {
        mAssembler->onPacketReceived(this);
    }
}

void ARTPSource::timeUpdate(uint32_t rtpTime, uint64_t ntpTime) {
    mLastNTPTime = ntpTime;
    mLastNTPTimeUpdateUs = ALooper::GetNowUs();

    sp<AMessage> notify = mNotify->dup();
    notify->setInt32("time-update", true);
    notify->setInt32("rtp-time", rtpTime);
    notify->setInt64("ntp-time", ntpTime);
    notify->post();
}

bool ARTPSource::queuePacket(const sp<ABuffer> &buffer) {
    uint32_t seqNum = (uint32_t)buffer->int32Data();

    if (mNumBuffersReceived++ == 0) {
        mHighestSeqNumber = seqNum;
        mQueue.push_back(buffer);
        return true;
    }

    // Only the lower 16-bit of the sequence numbers are transmitted,
    // derive the high-order bits by choosing the candidate closest
    // to the highest sequence number (extended to 32 bits) received so far.

    uint32_t seq1 = seqNum | (mHighestSeqNumber & 0xffff0000);
    uint32_t seq2 = seqNum | ((mHighestSeqNumber & 0xffff0000) + 0x10000);
    uint32_t seq3 = seqNum | ((mHighestSeqNumber & 0xffff0000) - 0x10000);
    uint32_t diff1 = AbsDiff(seq1, mHighestSeqNumber);
    uint32_t diff2 = AbsDiff(seq2, mHighestSeqNumber);
    uint32_t diff3 = AbsDiff(seq3, mHighestSeqNumber);

    if (diff1 < diff2) {
        if (diff1 < diff3) {
            // diff1 < diff2 ^ diff1 < diff3
            seqNum = seq1;
        } else {
            // diff3 <= diff1 < diff2
            seqNum = seq3;
        }
    } else if (diff2 < diff3) {
        // diff2 <= diff1 ^ diff2 < diff3
        seqNum = seq2;
    } else {
        // diff3 <= diff2 <= diff1
        seqNum = seq3;
    }

    if (seqNum > mHighestSeqNumber) {
        mHighestSeqNumber = seqNum;
    }

    buffer->setInt32Data(seqNum);

    List<sp<ABuffer> >::iterator it = mQueue.begin();
    while (it != mQueue.end() && (uint32_t)(*it)->int32Data() < seqNum) {
        ++it;
    }

    if (it != mQueue.end() && (uint32_t)(*it)->int32Data() == seqNum) {
        LOGW("Discarding duplicate buffer");
        return false;
    }

    mQueue.insert(it, buffer);

    return true;
}

void ARTPSource::byeReceived() {
    mAssembler->onByeReceived();
}

void ARTPSource::addFIR(const sp<ABuffer> &buffer) {
    if (!mIssueFIRRequests) {
        return;
    }

    int64_t nowUs = ALooper::GetNowUs();
    if (mLastFIRRequestUs >= 0 && mLastFIRRequestUs + 5000000ll > nowUs) {
        // Send FIR requests at most every 5 secs.
        return;
    }

    mLastFIRRequestUs = nowUs;

    if (buffer->size() + 20 > buffer->capacity()) {
        LOGW("RTCP buffer too small to accomodate FIR.");
        return;
    }

    uint8_t *data = buffer->data() + buffer->size();

    data[0] = 0x80 | 4;
    data[1] = 206;  // PSFB
    data[2] = 0;
    data[3] = 4;
    data[4] = kSourceID >> 24;
    data[5] = (kSourceID >> 16) & 0xff;
    data[6] = (kSourceID >> 8) & 0xff;
    data[7] = kSourceID & 0xff;

    data[8] = 0x00;  // SSRC of media source (unused)
    data[9] = 0x00;
    data[10] = 0x00;
    data[11] = 0x00;

    data[12] = mID >> 24;
    data[13] = (mID >> 16) & 0xff;
    data[14] = (mID >> 8) & 0xff;
    data[15] = mID & 0xff;

    data[16] = mNextFIRSeqNo++;  // Seq Nr.

    data[17] = 0x00;  // Reserved
    data[18] = 0x00;
    data[19] = 0x00;

    buffer->setRange(buffer->offset(), buffer->size() + 20);

    LOGV("Added FIR request.");
}

void ARTPSource::addReceiverReport(const sp<ABuffer> &buffer) {
    if (buffer->size() + 32 > buffer->capacity()) {
        LOGW("RTCP buffer too small to accomodate RR.");
        return;
    }

    uint8_t *data = buffer->data() + buffer->size();

    data[0] = 0x80 | 1;
    data[1] = 201;  // RR
    data[2] = 0;
    data[3] = 7;
    data[4] = kSourceID >> 24;
    data[5] = (kSourceID >> 16) & 0xff;
    data[6] = (kSourceID >> 8) & 0xff;
    data[7] = kSourceID & 0xff;

    data[8] = mID >> 24;
    data[9] = (mID >> 16) & 0xff;
    data[10] = (mID >> 8) & 0xff;
    data[11] = mID & 0xff;

    data[12] = 0x00;  // fraction lost

    data[13] = 0x00;  // cumulative lost
    data[14] = 0x00;
    data[15] = 0x00;

    data[16] = mHighestSeqNumber >> 24;
    data[17] = (mHighestSeqNumber >> 16) & 0xff;
    data[18] = (mHighestSeqNumber >> 8) & 0xff;
    data[19] = mHighestSeqNumber & 0xff;

    data[20] = 0x00;  // Interarrival jitter
    data[21] = 0x00;
    data[22] = 0x00;
    data[23] = 0x00;

    uint32_t LSR = 0;
    uint32_t DLSR = 0;
    if (mLastNTPTime != 0) {
        LSR = (mLastNTPTime >> 16) & 0xffffffff;

        DLSR = (uint32_t)
            ((ALooper::GetNowUs() - mLastNTPTimeUpdateUs) * 65536.0 / 1E6);
    }

    data[24] = LSR >> 24;
    data[25] = (LSR >> 16) & 0xff;
    data[26] = (LSR >> 8) & 0xff;
    data[27] = LSR & 0xff;

    data[28] = DLSR >> 24;
    data[29] = (DLSR >> 16) & 0xff;
    data[30] = (DLSR >> 8) & 0xff;
    data[31] = DLSR & 0xff;

    buffer->setRange(buffer->offset(), buffer->size() + 32);
}

}  // namespace android


