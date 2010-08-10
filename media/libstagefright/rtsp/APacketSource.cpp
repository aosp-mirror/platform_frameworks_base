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

#include "APacketSource.h"

#include "ASessionDescription.h"

#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/AString.h>
#include <media/stagefright/foundation/base64.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>
#include <utils/Vector.h>

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

static sp<ABuffer> MakeAVCCodecSpecificData(const char *params) {
    AString val;
    if (!GetAttribute(params, "profile-level-id", &val)) {
        return NULL;
    }

    sp<ABuffer> profileLevelID = decodeHex(val);
    CHECK(profileLevelID != NULL);
    CHECK_EQ(profileLevelID->size(), 3u);

    Vector<sp<ABuffer> > paramSets;

    size_t numSeqParameterSets = 0;
    size_t totalSeqParameterSetSize = 0;
    size_t numPicParameterSets = 0;
    size_t totalPicParameterSetSize = 0;

    if (!GetAttribute(params, "sprop-parameter-sets", &val)) {
        return NULL;
    }

    size_t start = 0;
    for (;;) {
        ssize_t commaPos = val.find(",", start);
        size_t end = (commaPos < 0) ? val.size() : commaPos;

        AString nalString(val, start, end - start);
        sp<ABuffer> nal = decodeBase64(nalString);
        CHECK(nal != NULL);
        CHECK_GT(nal->size(), 0u);
        CHECK_LE(nal->size(), 65535u);

        uint8_t nalType = nal->data()[0] & 0x1f;
        if (numSeqParameterSets == 0) {
            CHECK_EQ((unsigned)nalType, 7u);
        } else if (numPicParameterSets > 0) {
            CHECK_EQ((unsigned)nalType, 8u);
        }
        if (nalType == 7) {
            ++numSeqParameterSets;
            totalSeqParameterSetSize += nal->size();
        } else  {
            CHECK_EQ((unsigned)nalType, 8u);
            ++numPicParameterSets;
            totalPicParameterSetSize += nal->size();
        }

        paramSets.push(nal);

        if (commaPos < 0) {
            break;
        }

        start = commaPos + 1;
    }

    CHECK_LT(numSeqParameterSets, 32u);
    CHECK_LE(numPicParameterSets, 255u);

    size_t csdSize =
        1 + 3 + 1 + 1
        + 2 * numSeqParameterSets + totalSeqParameterSetSize
        + 1 + 2 * numPicParameterSets + totalPicParameterSetSize;

    sp<ABuffer> csd = new ABuffer(csdSize);
    uint8_t *out = csd->data();

    *out++ = 0x01;  // configurationVersion
    memcpy(out, profileLevelID->data(), 3);
    out += 3;
    *out++ = (0x3f << 2) | 1;  // lengthSize == 2 bytes
    *out++ = 0xe0 | numSeqParameterSets;

    for (size_t i = 0; i < numSeqParameterSets; ++i) {
        sp<ABuffer> nal = paramSets.editItemAt(i);

        *out++ = nal->size() >> 8;
        *out++ = nal->size() & 0xff;

        memcpy(out, nal->data(), nal->size());

        out += nal->size();
    }

    *out++ = numPicParameterSets;

    for (size_t i = 0; i < numPicParameterSets; ++i) {
        sp<ABuffer> nal = paramSets.editItemAt(i + numSeqParameterSets);

        *out++ = nal->size() >> 8;
        *out++ = nal->size() & 0xff;

        memcpy(out, nal->data(), nal->size());

        out += nal->size();
    }

    hexdump(csd->data(), csd->size());

    return csd;
}

sp<ABuffer> MakeAACCodecSpecificData(const char *params) {
    AString val;
    CHECK(GetAttribute(params, "config", &val));

    sp<ABuffer> config = decodeHex(val);
    CHECK(config != NULL);
    CHECK_GE(config->size(), 4u);

    const uint8_t *data = config->data();
    uint32_t x = data[0] << 24 | data[1] << 16 | data[2] << 8 | data[3];
    x = (x >> 1) & 0xffff;

    static const uint8_t kStaticESDS[] = {
        0x03, 22,
        0x00, 0x00,     // ES_ID
        0x00,           // streamDependenceFlag, URL_Flag, OCRstreamFlag

        0x04, 17,
        0x40,                       // Audio ISO/IEC 14496-3
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,
        0x00, 0x00, 0x00, 0x00,

        0x05, 2,
        // AudioSpecificInfo follows
    };

    sp<ABuffer> csd = new ABuffer(sizeof(kStaticESDS) + 2);
    memcpy(csd->data(), kStaticESDS, sizeof(kStaticESDS));
    csd->data()[sizeof(kStaticESDS)] = (x >> 8) & 0xff;
    csd->data()[sizeof(kStaticESDS) + 1] = x & 0xff;

    hexdump(csd->data(), csd->size());

    return csd;
}

APacketSource::APacketSource(
        const sp<ASessionDescription> &sessionDesc, size_t index)
    : mInitCheck(NO_INIT),
      mFormat(new MetaData),
      mEOSResult(OK),
      mFirstAccessUnit(true),
      mFirstAccessUnitNTP(0) {
    unsigned long PT;
    AString desc;
    AString params;
    sessionDesc->getFormatType(index, &PT, &desc, &params);

    int64_t durationUs;
    if (sessionDesc->getDurationUs(&durationUs)) {
        mFormat->setInt64(kKeyDuration, durationUs);
    } else {
        mFormat->setInt64(kKeyDuration, 60 * 60 * 1000000ll);
    }

    mInitCheck = OK;
    if (!strncmp(desc.c_str(), "H264/", 5)) {
        mFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);

        int32_t width, height;
        sessionDesc->getDimensions(index, PT, &width, &height);

        mFormat->setInt32(kKeyWidth, width);
        mFormat->setInt32(kKeyHeight, height);

        sp<ABuffer> codecSpecificData =
            MakeAVCCodecSpecificData(params.c_str());

        if (codecSpecificData != NULL) {
            mFormat->setData(
                    kKeyAVCC, 0,
                    codecSpecificData->data(), codecSpecificData->size());
        }
    } else if (!strncmp(desc.c_str(), "H263-2000/", 10)
            || !strncmp(desc.c_str(), "H263-1998/", 10)) {
        mFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_H263);

        int32_t width, height;
        sessionDesc->getDimensions(index, PT, &width, &height);

        mFormat->setInt32(kKeyWidth, width);
        mFormat->setInt32(kKeyHeight, height);
    } else if (!strncmp(desc.c_str(), "MP4A-LATM/", 10)) {
        mFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_AAC);

        int32_t sampleRate, numChannels;
        ASessionDescription::ParseFormatDesc(
                desc.c_str(), &sampleRate, &numChannels);

        mFormat->setInt32(kKeySampleRate, sampleRate);
        mFormat->setInt32(kKeyChannelCount, numChannels);

        sp<ABuffer> codecSpecificData =
            MakeAACCodecSpecificData(params.c_str());

        mFormat->setData(
                kKeyESDS, 0,
                codecSpecificData->data(), codecSpecificData->size());
    } else if (!strncmp(desc.c_str(), "AMR/", 4)) {
        mFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_AMR_NB);

        int32_t sampleRate, numChannels;
        ASessionDescription::ParseFormatDesc(
                desc.c_str(), &sampleRate, &numChannels);

        mFormat->setInt32(kKeySampleRate, sampleRate);
        mFormat->setInt32(kKeyChannelCount, numChannels);

        if (sampleRate != 8000 || numChannels != 1) {
            mInitCheck = ERROR_UNSUPPORTED;
        }
    } else if (!strncmp(desc.c_str(), "AMR-WB/", 7)) {
        mFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_AMR_WB);

        int32_t sampleRate, numChannels;
        ASessionDescription::ParseFormatDesc(
                desc.c_str(), &sampleRate, &numChannels);

        mFormat->setInt32(kKeySampleRate, sampleRate);
        mFormat->setInt32(kKeyChannelCount, numChannels);

        if (sampleRate != 16000 || numChannels != 1) {
            mInitCheck = ERROR_UNSUPPORTED;
        }
    } else {
        mInitCheck = ERROR_UNSUPPORTED;
    }
}

APacketSource::~APacketSource() {
}

status_t APacketSource::initCheck() const {
    return mInitCheck;
}

status_t APacketSource::start(MetaData *params) {
    mFirstAccessUnit = true;
    mFirstAccessUnitNTP = 0;

    return OK;
}

status_t APacketSource::stop() {
    return OK;
}

sp<MetaData> APacketSource::getFormat() {
    return mFormat;
}

status_t APacketSource::read(
        MediaBuffer **out, const ReadOptions *) {
    *out = NULL;

    Mutex::Autolock autoLock(mLock);
    while (mEOSResult == OK && mBuffers.empty()) {
        mCondition.wait(mLock);
    }

    if (!mBuffers.empty()) {
        const sp<ABuffer> buffer = *mBuffers.begin();

        MediaBuffer *mediaBuffer = new MediaBuffer(buffer->size());

        int64_t timeUs;
        CHECK(buffer->meta()->findInt64("timeUs", &timeUs));

        mediaBuffer->meta_data()->setInt64(kKeyTime, timeUs);

        memcpy(mediaBuffer->data(), buffer->data(), buffer->size());
        *out = mediaBuffer;

        mBuffers.erase(mBuffers.begin());
        return OK;
    }

    return mEOSResult;
}

void APacketSource::queueAccessUnit(const sp<ABuffer> &buffer) {
    int32_t damaged;
    if (buffer->meta()->findInt32("damaged", &damaged) && damaged) {
        LOG(INFO) << "discarding damaged AU";
        return;
    }

    uint64_t ntpTime;
    CHECK(buffer->meta()->findInt64(
                "ntp-time", (int64_t *)&ntpTime));

    if (mFirstAccessUnit) {
        mFirstAccessUnit = false;
        mFirstAccessUnitNTP = ntpTime;
    }

    if (ntpTime > mFirstAccessUnitNTP) {
        ntpTime -= mFirstAccessUnitNTP;
    } else {
        ntpTime = 0;
    }

    int64_t timeUs = (int64_t)(ntpTime * 1E6 / (1ll << 32));

    buffer->meta()->setInt64("timeUs", timeUs);

    Mutex::Autolock autoLock(mLock);
    mBuffers.push_back(buffer);
    mCondition.signal();
}

void APacketSource::signalEOS(status_t result) {
    CHECK(result != OK);

    Mutex::Autolock autoLock(mLock);
    mEOSResult = result;
    mCondition.signal();
}

int64_t APacketSource::getQueuedDuration(bool *eos) {
    Mutex::Autolock autoLock(mLock);

    *eos = (mEOSResult != OK);

    if (mBuffers.empty()) {
        return 0;
    }

    sp<ABuffer> buffer = *mBuffers.begin();

    uint64_t ntpTime;
    CHECK(buffer->meta()->findInt64(
                "ntp-time", (int64_t *)&ntpTime));

    int64_t firstTimeUs = (int64_t)(ntpTime * 1E6 / (1ll << 32));

    buffer = *--mBuffers.end();

    CHECK(buffer->meta()->findInt64(
                "ntp-time", (int64_t *)&ntpTime));

    int64_t lastTimeUs = (int64_t)(ntpTime * 1E6 / (1ll << 32));

    return lastTimeUs - firstTimeUs;
}

}  // namespace android
