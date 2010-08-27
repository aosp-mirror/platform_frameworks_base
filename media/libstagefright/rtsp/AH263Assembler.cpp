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

#include "AH263Assembler.h"

#include "ARTPSource.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/Utils.h>

namespace android {

AH263Assembler::AH263Assembler(const sp<AMessage> &notify)
    : mNotifyMsg(notify),
      mAccessUnitRTPTime(0),
      mNextExpectedSeqNoValid(false),
      mNextExpectedSeqNo(0),
      mAccessUnitDamaged(false) {
}

AH263Assembler::~AH263Assembler() {
}

ARTPAssembler::AssemblyStatus AH263Assembler::assembleMore(
        const sp<ARTPSource> &source) {
    AssemblyStatus status = addPacket(source);
    if (status == MALFORMED_PACKET) {
        mAccessUnitDamaged = true;
    }
    return status;
}

ARTPAssembler::AssemblyStatus AH263Assembler::addPacket(
        const sp<ARTPSource> &source) {
    List<sp<ABuffer> > *queue = source->queue();

    if (queue->empty()) {
        return NOT_ENOUGH_DATA;
    }

    if (mNextExpectedSeqNoValid) {
        List<sp<ABuffer> >::iterator it = queue->begin();
        while (it != queue->end()) {
            if ((uint32_t)(*it)->int32Data() >= mNextExpectedSeqNo) {
                break;
            }

            it = queue->erase(it);
        }

        if (queue->empty()) {
            return NOT_ENOUGH_DATA;
        }
    }

    sp<ABuffer> buffer = *queue->begin();

    if (!mNextExpectedSeqNoValid) {
        mNextExpectedSeqNoValid = true;
        mNextExpectedSeqNo = (uint32_t)buffer->int32Data();
    } else if ((uint32_t)buffer->int32Data() != mNextExpectedSeqNo) {
#if VERBOSE
        LOG(VERBOSE) << "Not the sequence number I expected";
#endif

        return WRONG_SEQUENCE_NUMBER;
    }

    uint32_t rtpTime;
    CHECK(buffer->meta()->findInt32("rtp-time", (int32_t *)&rtpTime));

    if (mPackets.size() > 0 && rtpTime != mAccessUnitRTPTime) {
        submitAccessUnit();
    }
    mAccessUnitRTPTime = rtpTime;

    // hexdump(buffer->data(), buffer->size());

    if (buffer->size() < 2) {
        queue->erase(queue->begin());
        ++mNextExpectedSeqNo;

        return MALFORMED_PACKET;
    }

    unsigned payloadHeader = U16_AT(buffer->data());
    CHECK_EQ(payloadHeader >> 11, 0u);  // RR=0
    unsigned P = (payloadHeader >> 10) & 1;
    CHECK_EQ((payloadHeader >> 9) & 1, 0u);  // V=0
    CHECK_EQ((payloadHeader >> 3) & 0x3f, 0u);  // PLEN=0
    CHECK_EQ(payloadHeader & 7, 0u);  // PEBIT=0

    if (P) {
        buffer->data()[0] = 0x00;
        buffer->data()[1] = 0x00;
    } else {
        buffer->setRange(buffer->offset() + 2, buffer->size() - 2);
    }

    mPackets.push_back(buffer);

    queue->erase(queue->begin());
    ++mNextExpectedSeqNo;

    return OK;
}

void AH263Assembler::submitAccessUnit() {
    CHECK(!mPackets.empty());

#if VERBOSE
    LOG(VERBOSE) << "Access unit complete (" << mPackets.size() << " packets)";
#endif

    size_t totalSize = 0;
    List<sp<ABuffer> >::iterator it = mPackets.begin();
    while (it != mPackets.end()) {
        const sp<ABuffer> &unit = *it;

        totalSize += unit->size();
        ++it;
    }

    sp<ABuffer> accessUnit = new ABuffer(totalSize);
    size_t offset = 0;
    it = mPackets.begin();
    while (it != mPackets.end()) {
        const sp<ABuffer> &unit = *it;

        memcpy((uint8_t *)accessUnit->data() + offset,
               unit->data(), unit->size());

        offset += unit->size();

        ++it;
    }

    CopyTimes(accessUnit, *mPackets.begin());

#if 0
    printf(mAccessUnitDamaged ? "X" : ".");
    fflush(stdout);
#endif

    if (mAccessUnitDamaged) {
        accessUnit->meta()->setInt32("damaged", true);
    }

    mPackets.clear();
    mAccessUnitDamaged = false;

    sp<AMessage> msg = mNotifyMsg->dup();
    msg->setObject("access-unit", accessUnit);
    msg->post();
}

void AH263Assembler::packetLost() {
    CHECK(mNextExpectedSeqNoValid);
    ++mNextExpectedSeqNo;

    mAccessUnitDamaged = true;
}

void AH263Assembler::onByeReceived() {
    sp<AMessage> msg = mNotifyMsg->dup();
    msg->setInt32("eos", true);
    msg->post();
}

}  // namespace android

