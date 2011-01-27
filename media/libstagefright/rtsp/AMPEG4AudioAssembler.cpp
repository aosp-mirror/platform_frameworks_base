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

#include "AMPEG4AudioAssembler.h"

#include "ARTPSource.h"

#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ABitReader.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaErrors.h>

#include <ctype.h>

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

static sp<ABuffer> decodeHex(const AString &s) {
    if ((s.size() % 2) != 0) {
        return NULL;
    }

    size_t outLen = s.size() / 2;
    sp<ABuffer> buffer = new ABuffer(outLen);
    uint8_t *out = buffer->data();

    uint8_t accum = 0;
    for (size_t i = 0; i < s.size(); ++i) {
        char c = s.c_str()[i];
        unsigned value;
        if (c >= '0' && c <= '9') {
            value = c - '0';
        } else if (c >= 'a' && c <= 'f') {
            value = c - 'a' + 10;
        } else if (c >= 'A' && c <= 'F') {
            value = c - 'A' + 10;
        } else {
            return NULL;
        }

        accum = (accum << 4) | value;

        if (i & 1) {
            *out++ = accum;

            accum = 0;
        }
    }

    return buffer;
}

static status_t parseAudioObjectType(
        ABitReader *bits, unsigned *audioObjectType) {
    *audioObjectType = bits->getBits(5);
    if ((*audioObjectType) == 31) {
        *audioObjectType = 32 + bits->getBits(6);
    }

    return OK;
}

static status_t parseGASpecificConfig(
        ABitReader *bits,
        unsigned audioObjectType, unsigned channelConfiguration) {
    unsigned frameLengthFlag = bits->getBits(1);
    unsigned dependsOnCoreCoder = bits->getBits(1);
    if (dependsOnCoreCoder) {
        /* unsigned coreCoderDelay = */bits->getBits(1);
    }
    unsigned extensionFlag = bits->getBits(1);

    if (!channelConfiguration) {
        // program_config_element
        return ERROR_UNSUPPORTED;  // XXX to be implemented
    }

    if (audioObjectType == 6 || audioObjectType == 20) {
        /* unsigned layerNr = */bits->getBits(3);
    }

    if (extensionFlag) {
        if (audioObjectType == 22) {
            /* unsigned numOfSubFrame = */bits->getBits(5);
            /* unsigned layerLength = */bits->getBits(11);
        } else if (audioObjectType == 17 || audioObjectType == 19
                || audioObjectType == 20 || audioObjectType == 23) {
            /* unsigned aacSectionDataResilienceFlag = */bits->getBits(1);
            /* unsigned aacScalefactorDataResilienceFlag = */bits->getBits(1);
            /* unsigned aacSpectralDataResilienceFlag = */bits->getBits(1);
        }

        unsigned extensionFlag3 = bits->getBits(1);
        CHECK_EQ(extensionFlag3, 0u);  // TBD in version 3
    }

    return OK;
}

static status_t parseAudioSpecificConfig(ABitReader *bits) {
    unsigned audioObjectType;
    CHECK_EQ(parseAudioObjectType(bits, &audioObjectType), (status_t)OK);

    unsigned samplingFreqIndex = bits->getBits(4);
    if (samplingFreqIndex == 0x0f) {
        /* unsigned samplingFrequency = */bits->getBits(24);
    }

    unsigned channelConfiguration = bits->getBits(4);

    unsigned extensionAudioObjectType = 0;
    unsigned sbrPresent = 0;

    if (audioObjectType == 5) {
        extensionAudioObjectType = audioObjectType;
        sbrPresent = 1;
        unsigned extensionSamplingFreqIndex = bits->getBits(4);
        if (extensionSamplingFreqIndex == 0x0f) {
            /* unsigned extensionSamplingFrequency = */bits->getBits(24);
        }
        CHECK_EQ(parseAudioObjectType(bits, &audioObjectType), (status_t)OK);
    }

    CHECK((audioObjectType >= 1 && audioObjectType <= 4)
        || (audioObjectType >= 6 && audioObjectType <= 7)
        || audioObjectType == 17
        || (audioObjectType >= 19 && audioObjectType <= 23));

    CHECK_EQ(parseGASpecificConfig(
                bits, audioObjectType, channelConfiguration), (status_t)OK);

    if (audioObjectType == 17
            || (audioObjectType >= 19 && audioObjectType <= 27)) {
        unsigned epConfig = bits->getBits(2);
        if (epConfig == 2 || epConfig == 3) {
            // ErrorProtectionSpecificConfig
            return ERROR_UNSUPPORTED;  // XXX to be implemented

            if (epConfig == 3) {
                unsigned directMapping = bits->getBits(1);
                CHECK_EQ(directMapping, 1u);
            }
        }
    }

#if 0
    // This is not supported here as the upper layers did not explicitly
    // signal the length of AudioSpecificConfig.

    if (extensionAudioObjectType != 5 && bits->numBitsLeft() >= 16) {
        unsigned syncExtensionType = bits->getBits(11);
        if (syncExtensionType == 0x2b7) {
            CHECK_EQ(parseAudioObjectType(bits, &extensionAudioObjectType),
                     (status_t)OK);

            sbrPresent = bits->getBits(1);

            if (sbrPresent == 1) {
                unsigned extensionSamplingFreqIndex = bits->getBits(4);
                if (extensionSamplingFreqIndex == 0x0f) {
                    /* unsigned extensionSamplingFrequency = */bits->getBits(24);
                }
            }
        }
    }
#endif

    return OK;
}

static status_t parseStreamMuxConfig(
        ABitReader *bits,
        unsigned *numSubFrames,
        unsigned *frameLengthType,
        bool *otherDataPresent,
        unsigned *otherDataLenBits) {
    unsigned audioMuxVersion = bits->getBits(1);

    unsigned audioMuxVersionA = 0;
    if (audioMuxVersion == 1) {
        audioMuxVersionA = bits->getBits(1);
    }

    CHECK_EQ(audioMuxVersionA, 0u);  // otherwise future spec

    if (audioMuxVersion != 0) {
        return ERROR_UNSUPPORTED;  // XXX to be implemented;
    }
    CHECK_EQ(audioMuxVersion, 0u);  // XXX to be implemented

    unsigned allStreamsSameTimeFraming = bits->getBits(1);
    CHECK_EQ(allStreamsSameTimeFraming, 1u);  // There's only one stream.

    *numSubFrames = bits->getBits(6);
    unsigned numProgram = bits->getBits(4);
    CHECK_EQ(numProgram, 0u);  // disabled in RTP LATM

    unsigned numLayer = bits->getBits(3);
    CHECK_EQ(numLayer, 0u);  // disabled in RTP LATM

    if (audioMuxVersion == 0) {
        // AudioSpecificConfig
        CHECK_EQ(parseAudioSpecificConfig(bits), (status_t)OK);
    } else {
        TRESPASS();  // XXX to be implemented
    }

    *frameLengthType = bits->getBits(3);
    switch (*frameLengthType) {
        case 0:
        {
            /* unsigned bufferFullness = */bits->getBits(8);

            // The "coreFrameOffset" does not apply since there's only
            // a single layer.
            break;
        }

        case 1:
        {
            /* unsigned frameLength = */bits->getBits(9);
            break;
        }

        case 3:
        case 4:
        case 5:
        {
            /* unsigned CELPframeLengthTableIndex = */bits->getBits(6);
            break;
        }

        case 6:
        case 7:
        {
            /* unsigned HVXCframeLengthTableIndex = */bits->getBits(1);
            break;
        }

        default:
            break;
    }

    *otherDataPresent = bits->getBits(1);
    *otherDataLenBits = 0;
    if (*otherDataPresent) {
        if (audioMuxVersion == 1) {
            TRESPASS();  // XXX to be implemented
        } else {
            *otherDataLenBits = 0;

            unsigned otherDataLenEsc;
            do {
                (*otherDataLenBits) <<= 8;
                otherDataLenEsc = bits->getBits(1);
                unsigned otherDataLenTmp = bits->getBits(8);
                (*otherDataLenBits) += otherDataLenTmp;
            } while (otherDataLenEsc);
        }
    }

    unsigned crcCheckPresent = bits->getBits(1);
    if (crcCheckPresent) {
        /* unsigned crcCheckSum = */bits->getBits(8);
    }

    return OK;
}

sp<ABuffer> AMPEG4AudioAssembler::removeLATMFraming(const sp<ABuffer> &buffer) {
    CHECK(!mMuxConfigPresent);  // XXX to be implemented

    sp<ABuffer> out = new ABuffer(buffer->size());
    out->setRange(0, 0);

    size_t offset = 0;
    uint8_t *ptr = buffer->data();

    for (size_t i = 0; i <= mNumSubFrames; ++i) {
        // parse PayloadLengthInfo

        unsigned payloadLength = 0;

        switch (mFrameLengthType) {
            case 0:
            {
                unsigned muxSlotLengthBytes = 0;
                unsigned tmp;
                do {
                    CHECK_LT(offset, buffer->size());
                    tmp = ptr[offset++];
                    muxSlotLengthBytes += tmp;
                } while (tmp == 0xff);

                payloadLength = muxSlotLengthBytes;
                break;
            }

            default:
                TRESPASS();  // XXX to be implemented
                break;
        }

        CHECK_LE(offset + payloadLength, buffer->size());

        memcpy(out->data() + out->size(), &ptr[offset], payloadLength);
        out->setRange(0, out->size() + payloadLength);

        offset += payloadLength;

        if (mOtherDataPresent) {
            // We want to stay byte-aligned.

            CHECK((mOtherDataLenBits % 8) == 0);
            CHECK_LE(offset + (mOtherDataLenBits / 8), buffer->size());
            offset += mOtherDataLenBits / 8;
        }
    }

    if (offset < buffer->size()) {
        LOGI("ignoring %d bytes of trailing data", buffer->size() - offset);
    }
    CHECK_LE(offset, buffer->size());

    return out;
}

AMPEG4AudioAssembler::AMPEG4AudioAssembler(
        const sp<AMessage> &notify, const AString &params)
    : mNotifyMsg(notify),
      mMuxConfigPresent(false),
      mAccessUnitRTPTime(0),
      mNextExpectedSeqNoValid(false),
      mNextExpectedSeqNo(0),
      mAccessUnitDamaged(false) {
    AString val;
    if (!GetAttribute(params.c_str(), "cpresent", &val)) {
        mMuxConfigPresent = true;
    } else if (val == "0") {
        mMuxConfigPresent = false;
    } else {
        CHECK(val == "1");
        mMuxConfigPresent = true;
    }

    CHECK(GetAttribute(params.c_str(), "config", &val));

    sp<ABuffer> config = decodeHex(val);
    CHECK(config != NULL);

    ABitReader bits(config->data(), config->size());
    status_t err = parseStreamMuxConfig(
            &bits, &mNumSubFrames, &mFrameLengthType,
            &mOtherDataPresent, &mOtherDataLenBits);

    CHECK_EQ(err, (status_t)NO_ERROR);
}

AMPEG4AudioAssembler::~AMPEG4AudioAssembler() {
}

ARTPAssembler::AssemblyStatus AMPEG4AudioAssembler::assembleMore(
        const sp<ARTPSource> &source) {
    AssemblyStatus status = addPacket(source);
    if (status == MALFORMED_PACKET) {
        mAccessUnitDamaged = true;
    }
    return status;
}

ARTPAssembler::AssemblyStatus AMPEG4AudioAssembler::addPacket(
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

    mPackets.push_back(buffer);

    queue->erase(queue->begin());
    ++mNextExpectedSeqNo;

    return OK;
}

void AMPEG4AudioAssembler::submitAccessUnit() {
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

        ++it;
    }

    accessUnit = removeLATMFraming(accessUnit);
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

void AMPEG4AudioAssembler::packetLost() {
    CHECK(mNextExpectedSeqNoValid);
    ++mNextExpectedSeqNo;

    mAccessUnitDamaged = true;
}

void AMPEG4AudioAssembler::onByeReceived() {
    sp<AMessage> msg = mNotifyMsg->dup();
    msg->setInt32("eos", true);
    msg->post();
}

}  // namespace android
