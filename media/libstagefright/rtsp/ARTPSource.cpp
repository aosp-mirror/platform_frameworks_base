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

#include "ARTPSource.h"

#include "AAVCAssembler.h"
#include "AMPEG4AudioAssembler.h"
#include "ASessionDescription.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>

#define VERBOSE         0

namespace android {

ARTPSource::ARTPSource(
        uint32_t id,
        const sp<ASessionDescription> &sessionDesc, size_t index,
        const sp<AMessage> &notify)
    : mID(id),
      mHighestSeqNumber(0),
      mNumBuffersReceived(0),
      mNumTimes(0) {
    unsigned long PT;
    AString desc;
    AString params;
    sessionDesc->getFormatType(index, &PT, &desc, &params);

    if (!strncmp(desc.c_str(), "H264/", 5)) {
        mAssembler = new AAVCAssembler(notify);
    } else if (!strncmp(desc.c_str(), "MP4A-LATM", 9)) {
        mAssembler = new AMPEG4AudioAssembler(notify);
    } else {
        TRESPASS();
    }
}

static uint32_t AbsDiff(uint32_t seq1, uint32_t seq2) {
    return seq1 > seq2 ? seq1 - seq2 : seq2 - seq1;
}

void ARTPSource::processRTPPacket(const sp<ABuffer> &buffer) {
    if (queuePacket(buffer) && mNumTimes == 2 && mAssembler != NULL) {
        mAssembler->onPacketReceived(this);
    }

    dump();
}

void ARTPSource::timeUpdate(uint32_t rtpTime, uint64_t ntpTime) {
#if VERBOSE
    LOG(VERBOSE) << "timeUpdate";
#endif

    if (mNumTimes == 2) {
        mNTPTime[0] = mNTPTime[1];
        mRTPTime[0] = mRTPTime[1];
        mNumTimes = 1;
    }
    mNTPTime[mNumTimes] = ntpTime;
    mRTPTime[mNumTimes++] = rtpTime;

    if (mNumTimes == 2) {
        for (List<sp<ABuffer> >::iterator it = mQueue.begin();
             it != mQueue.end(); ++it) {
            sp<AMessage> meta = (*it)->meta();

            uint32_t rtpTime;
            CHECK(meta->findInt32("rtp-time", (int32_t *)&rtpTime));

            meta->setInt64("ntp-time", RTP2NTP(rtpTime));
        }
    }
}

bool ARTPSource::queuePacket(const sp<ABuffer> &buffer) {
    uint32_t seqNum = (uint32_t)buffer->int32Data();

    if (mNumTimes == 2) {
        sp<AMessage> meta = buffer->meta();

        uint32_t rtpTime;
        CHECK(meta->findInt32("rtp-time", (int32_t *)&rtpTime));

        meta->setInt64("ntp-time", RTP2NTP(rtpTime));
    }

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
        LOG(WARNING) << "Discarding duplicate buffer";
        return false;
    }

    mQueue.insert(it, buffer);

    return true;
}

void ARTPSource::dump() const {
    if ((mNumBuffersReceived % 128) != 0) {
        return;
    }

#if 0
    if (mAssembler == NULL) {
        char tmp[20];
        sprintf(tmp, "0x%08x", mID);

        int32_t numMissing = 0;

        if (!mQueue.empty()) {
            List<sp<ABuffer> >::const_iterator it = mQueue.begin();
            uint32_t expectedSeqNum = (uint32_t)(*it)->int32Data();
            ++expectedSeqNum;
            ++it;

            for (; it != mQueue.end(); ++it) {
                uint32_t seqNum = (uint32_t)(*it)->int32Data();
                CHECK_GE(seqNum, expectedSeqNum);

                if (seqNum != expectedSeqNum) {
                    numMissing += seqNum - expectedSeqNum;
                    expectedSeqNum = seqNum;
                }

                ++expectedSeqNum;
            }
        }

        LOG(VERBOSE) << "[" << tmp << "] Missing " << numMissing
             << " / " << (mNumBuffersReceived + numMissing) << " packets. ("
             << (100.0 * numMissing / (mNumBuffersReceived + numMissing))
             << " %%)";
    }
#endif

#if 0
    AString out;
    
    out.append(tmp);
    out.append(" [");

    List<sp<ABuffer> >::const_iterator it = mQueue.begin();
    while (it != mQueue.end()) {
        uint32_t start = (uint32_t)(*it)->int32Data();

        out.append(start);

        ++it;
        uint32_t expected = start + 1;

        while (it != mQueue.end()) {
            uint32_t seqNum = (uint32_t)(*it)->int32Data();

            if (seqNum != expected) {
                if (expected > start + 1) {
                    out.append("-");
                    out.append(expected - 1);
                }
                out.append(", ");
                break;
            }

            ++it;
            ++expected;
        }

        if (it == mQueue.end()) {
            if (expected > start + 1) {
                out.append("-");
                out.append(expected - 1);
            }
        }
    }

    out.append("]");

    LOG(VERBOSE) << out;
#endif
}

uint64_t ARTPSource::RTP2NTP(uint32_t rtpTime) const {
    CHECK_EQ(mNumTimes, 2u);

    return mNTPTime[0] + (double)(mNTPTime[1] - mNTPTime[0])
            * ((double)rtpTime - (double)mRTPTime[0])
            / (double)(mRTPTime[1] - mRTPTime[0]);
}

}  // namespace android


