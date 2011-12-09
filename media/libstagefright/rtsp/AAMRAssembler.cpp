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
#define LOG_TAG "AAMRAssembler"
#include <utils/Log.h>

#include "AAMRAssembler.h"

#include "ARTPSource.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/Utils.h>

namespace android {

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
        if (len == keyLen && !strncmp(s, key, keyLen)) {
            value->setTo("1");
            return true;
        }

        if (colonPos == NULL) {
            return false;
        }

        s = colonPos + 1;
    }
}

AAMRAssembler::AAMRAssembler(
        const sp<AMessage> &notify, bool isWide, const AString &params)
    : mIsWide(isWide),
      mNotifyMsg(notify),
      mNextExpectedSeqNoValid(false),
      mNextExpectedSeqNo(0) {
    AString value;
    CHECK(GetAttribute(params.c_str(), "octet-align", &value) && value == "1");
    CHECK(!GetAttribute(params.c_str(), "crc", &value) || value == "0");
    CHECK(!GetAttribute(params.c_str(), "interleaving", &value));
}

AAMRAssembler::~AAMRAssembler() {
}

ARTPAssembler::AssemblyStatus AAMRAssembler::assembleMore(
        const sp<ARTPSource> &source) {
    return addPacket(source);
}

static size_t getFrameSize(bool isWide, unsigned FT) {
    static const size_t kFrameSizeNB[9] = {
        95, 103, 118, 134, 148, 159, 204, 244, 39
    };
    static const size_t kFrameSizeWB[10] = {
        132, 177, 253, 285, 317, 365, 397, 461, 477, 40
    };

    if (FT == 15) {
        return 1;
    }

    size_t frameSize = isWide ? kFrameSizeWB[FT] : kFrameSizeNB[FT];

    // Round up bits to bytes and add 1 for the header byte.
    frameSize = (frameSize + 7) / 8 + 1;

    return frameSize;
}

ARTPAssembler::AssemblyStatus AAMRAssembler::addPacket(
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
        LOGV("Not the sequence number I expected");

        return WRONG_SEQUENCE_NUMBER;
    }

    // hexdump(buffer->data(), buffer->size());

    if (buffer->size() < 1) {
        queue->erase(queue->begin());
        ++mNextExpectedSeqNo;

        LOGV("AMR packet too short.");

        return MALFORMED_PACKET;
    }

    unsigned payloadHeader = buffer->data()[0];
    unsigned CMR = payloadHeader >> 4;
    CHECK_EQ(payloadHeader & 0x0f, 0u);  // RR

    Vector<uint8_t> tableOfContents;

    size_t offset = 1;
    size_t totalSize = 0;
    for (;;) {
        if (offset >= buffer->size()) {
            queue->erase(queue->begin());
            ++mNextExpectedSeqNo;

            LOGV("Unable to parse TOC.");

            return MALFORMED_PACKET;
        }

        uint8_t toc = buffer->data()[offset++];

        unsigned FT = (toc >> 3) & 0x0f;
        if ((toc & 3) != 0
                || (mIsWide && FT > 9 && FT != 15)
                || (!mIsWide && FT > 8 && FT != 15)) {
            queue->erase(queue->begin());
            ++mNextExpectedSeqNo;

            LOGV("Illegal TOC entry.");

            return MALFORMED_PACKET;
        }

        totalSize += getFrameSize(mIsWide, (toc >> 3) & 0x0f);

        tableOfContents.push(toc);

        if (0 == (toc & 0x80)) {
            break;
        }
    }

    sp<ABuffer> accessUnit = new ABuffer(totalSize);
    CopyTimes(accessUnit, buffer);

    size_t dstOffset = 0;
    for (size_t i = 0; i < tableOfContents.size(); ++i) {
        uint8_t toc = tableOfContents[i];

        size_t frameSize = getFrameSize(mIsWide, (toc >> 3) & 0x0f);

        if (offset + frameSize - 1 > buffer->size()) {
            queue->erase(queue->begin());
            ++mNextExpectedSeqNo;

            LOGV("AMR packet too short.");

            return MALFORMED_PACKET;
        }

        accessUnit->data()[dstOffset++] = toc;
        memcpy(accessUnit->data() + dstOffset,
               buffer->data() + offset, frameSize - 1);

        offset += frameSize - 1;
        dstOffset += frameSize - 1;
    }

    sp<AMessage> msg = mNotifyMsg->dup();
    msg->setObject("access-unit", accessUnit);
    msg->post();

    queue->erase(queue->begin());
    ++mNextExpectedSeqNo;

    return OK;
}

void AAMRAssembler::packetLost() {
    CHECK(mNextExpectedSeqNoValid);
    ++mNextExpectedSeqNo;
}

void AAMRAssembler::onByeReceived() {
    sp<AMessage> msg = mNotifyMsg->dup();
    msg->setInt32("eos", true);
    msg->post();
}

}  // namespace android
