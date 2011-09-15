/*
 * Copyright (C) 2011 The Android Open Source Project
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

#define LOG_TAG "LibAAH_RTP"
#include <utils/Log.h>

#include <arpa/inet.h>
#include <string.h>

#include <media/stagefright/MediaDebug.h>

#include "aah_tx_packet.h"

namespace android {

const int TRTPPacket::kRTPHeaderLen;
const uint32_t TRTPPacket::kTRTPEpochMask;

TRTPPacket::~TRTPPacket() {
    delete mPacket;
}

/*** TRTP packet properties ***/

void TRTPPacket::setSeqNumber(uint16_t val) {
    mSeqNumber = val;

    if (mIsPacked) {
        const int kTRTPSeqNumberOffset = 2;
        uint16_t* buf = reinterpret_cast<uint16_t*>(
            mPacket + kTRTPSeqNumberOffset);
        *buf = htons(mSeqNumber);
    }
}

uint16_t TRTPPacket::getSeqNumber() const {
    return mSeqNumber;
}

void TRTPPacket::setPTS(int64_t val) {
    CHECK(!mIsPacked);
    mPTS = val;
    mPTSValid = true;
}

int64_t TRTPPacket::getPTS() const {
    return mPTS;
}

void TRTPPacket::setEpoch(uint32_t val) {
    mEpoch = val;

    if (mIsPacked) {
        const int kTRTPEpochOffset = 8;
        uint32_t* buf = reinterpret_cast<uint32_t*>(
            mPacket + kTRTPEpochOffset);
        uint32_t val = ntohl(*buf);
        val &= ~(kTRTPEpochMask << kTRTPEpochShift);
        val |= (mEpoch & kTRTPEpochMask) << kTRTPEpochShift;
        *buf = htonl(val);
    }
}

void TRTPPacket::setProgramID(uint16_t val) {
    CHECK(!mIsPacked);
    mProgramID = val;
}

void TRTPPacket::setSubstreamID(uint16_t val) {
    CHECK(!mIsPacked);
    mSubstreamID = val;
}


void TRTPPacket::setClockTransform(const LinearTransform& trans) {
    CHECK(!mIsPacked);
    mClockTranform = trans;
    mClockTranformValid = true;
}

uint8_t* TRTPPacket::getPacket() const {
    CHECK(mIsPacked);
    return mPacket;
}

int TRTPPacket::getPacketLen() const {
    CHECK(mIsPacked);
    return mPacketLen;
}

void TRTPPacket::setExpireTime(nsecs_t val) {
    CHECK(!mIsPacked);
    mExpireTime = val;
}

nsecs_t TRTPPacket::getExpireTime() const {
    return mExpireTime;
}

/*** TRTP audio packet properties ***/

void TRTPAudioPacket::setCodecType(TRTPAudioCodecType val) {
    CHECK(!mIsPacked);
    mCodecType = val;
}

void TRTPAudioPacket::setRandomAccessPoint(bool val) {
    CHECK(!mIsPacked);
    mRandomAccessPoint = val;
}

void TRTPAudioPacket::setDropable(bool val) {
    CHECK(!mIsPacked);
    mDropable = val;
}

void TRTPAudioPacket::setDiscontinuity(bool val) {
    CHECK(!mIsPacked);
    mDiscontinuity = val;
}

void TRTPAudioPacket::setEndOfStream(bool val) {
    CHECK(!mIsPacked);
    mEndOfStream = val;
}

void TRTPAudioPacket::setVolume(uint8_t val) {
    CHECK(!mIsPacked);
    mVolume = val;
}

void TRTPAudioPacket::setAccessUnitData(void* data, int len) {
    CHECK(!mIsPacked);
    mAccessUnitData = data;
    mAccessUnitLen = len;
}

/*** TRTP control packet properties ***/

void TRTPControlPacket::setCommandID(TRTPCommandID val) {
    CHECK(!mIsPacked);
    mCommandID = val;
}

/*** TRTP packet serializers ***/

void TRTPPacket::writeU8(uint8_t*& buf, uint8_t val) {
    *buf = val;
    buf++;
}

void TRTPPacket::writeU16(uint8_t*& buf, uint16_t val) {
    *reinterpret_cast<uint16_t*>(buf) = htons(val);
    buf += 2;
}

void TRTPPacket::writeU32(uint8_t*& buf, uint32_t val) {
    *reinterpret_cast<uint32_t*>(buf) = htonl(val);
    buf += 4;
}

void TRTPPacket::writeU64(uint8_t*& buf, uint64_t val) {
    buf[0] = static_cast<uint8_t>(val >> 56);
    buf[1] = static_cast<uint8_t>(val >> 48);
    buf[2] = static_cast<uint8_t>(val >> 40);
    buf[3] = static_cast<uint8_t>(val >> 32);
    buf[4] = static_cast<uint8_t>(val >> 24);
    buf[5] = static_cast<uint8_t>(val >> 16);
    buf[6] = static_cast<uint8_t>(val >>  8);
    buf[7] = static_cast<uint8_t>(val);
    buf += 8;
}

void TRTPPacket::writeTRTPHeader(uint8_t*& buf,
                                 bool isFirstFragment,
                                 int totalPacketLen) {
    // RTP header
    writeU8(buf,
            ((mVersion & 0x03) << 6) |
            (static_cast<int>(mPadding) << 5) |
            (static_cast<int>(mExtension) << 4) |
            (mCsrcCount & 0x0F));
    writeU8(buf,
            (static_cast<int>(isFirstFragment) << 7) |
            (mPayloadType & 0x7F));
    writeU16(buf, mSeqNumber);
    if (isFirstFragment && mPTSValid) {
        writeU32(buf, mPTS & 0xFFFFFFFF);
    } else {
        writeU32(buf, 0);
    }
    writeU32(buf,
            ((mEpoch & kTRTPEpochMask) << kTRTPEpochShift) |
            ((mProgramID & 0x1F) << 5) |
            (mSubstreamID & 0x1F));

    // TRTP header
    writeU8(buf, mTRTPVersion);
    writeU8(buf,
            ((mTRTPHeaderType & 0x0F) << 4) |
            (mClockTranformValid ? 0x02 : 0x00) |
            (mPTSValid ? 0x01 : 0x00));
    writeU32(buf, totalPacketLen - kRTPHeaderLen);
    if (mPTSValid) {
        writeU32(buf, mPTS >> 32);
    }

    if (mClockTranformValid) {
        writeU64(buf, mClockTranform.a_zero);
        writeU32(buf, mClockTranform.a_to_b_numer);
        writeU32(buf, mClockTranform.a_to_b_denom);
        writeU64(buf, mClockTranform.b_zero);
    }
}

bool TRTPAudioPacket::pack() {
    if (mIsPacked) {
        return false;
    }

    int packetLen = kRTPHeaderLen +
                    mAccessUnitLen +
                    TRTPHeaderLen();

    // TODO : support multiple fragments
    const int kMaxUDPPayloadLen = 65507;
    if (packetLen > kMaxUDPPayloadLen) {
        return false;
    }

    mPacket = new uint8_t[packetLen];
    if (!mPacket) {
        return false;
    }

    mPacketLen = packetLen;

    uint8_t* cur = mPacket;

    writeTRTPHeader(cur, true, packetLen);
    writeU8(cur, mCodecType);
    writeU8(cur,
            (static_cast<int>(mRandomAccessPoint) << 3) |
            (static_cast<int>(mDropable) << 2) |
            (static_cast<int>(mDiscontinuity) << 1) |
            (static_cast<int>(mEndOfStream)));
    writeU8(cur, mVolume);

    memcpy(cur, mAccessUnitData, mAccessUnitLen);

    mIsPacked = true;
    return true;
}

int TRTPPacket::TRTPHeaderLen() const {
    // 6 bytes for version, payload type, flags and length.  An additional 4 if
    // there are upper timestamp bits present and another 24 if there is a clock
    // transformation present.
    return 6 +
           (mClockTranformValid ? 24 : 0) +
           (mPTSValid ? 4 : 0);
}

int TRTPAudioPacket::TRTPHeaderLen() const {
    // TRTPPacket::TRTPHeaderLen() for the base TRTPHeader.  3 bytes for audio's
    // codec type, flags and volume field.  Another 5 bytes if the codec type is
    // PCM and we are sending sample rate/channel count. as well as however long
    // the aux data (if present) is.

    int pcmParamLength;
    switch(mCodecType) {
        case kCodecPCMBigEndian:
        case kCodecPCMLittleEndian:
            pcmParamLength = 5;
            break;

        default:
            pcmParamLength = 0;
            break;
    }


    // TODO : properly compute aux data length.  Currently, nothing
    // uses aux data, so its length is always 0.
    int auxDataLength = 0;
    return TRTPPacket::TRTPHeaderLen() +
           3 +
           auxDataLength +
           pcmParamLength;
}

bool TRTPControlPacket::pack() {
    if (mIsPacked) {
        return false;
    }

    // command packets contain a 2-byte command ID
    int packetLen = kRTPHeaderLen +
                    TRTPHeaderLen() +
                    2;

    mPacket = new uint8_t[packetLen];
    if (!mPacket) {
        return false;
    }

    mPacketLen = packetLen;

    uint8_t* cur = mPacket;

    writeTRTPHeader(cur, true, packetLen);
    writeU16(cur, mCommandID);

    mIsPacked = true;
    return true;
}

}  // namespace android
