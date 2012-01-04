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
#define LOG_TAG "APacketSource"
#include <utils/Log.h>

#include "APacketSource.h"

#include "ARawAudioAssembler.h"
#include "ASessionDescription.h"

#include "avc_utils.h"

#include <ctype.h>

#include <media/stagefright/foundation/ABitReader.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/AString.h>
#include <media/stagefright/foundation/base64.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <utils/Vector.h>

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

static sp<ABuffer> MakeAVCCodecSpecificData(
        const char *params, int32_t *width, int32_t *height) {
    *width = 0;
    *height = 0;

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

        if (i == 0) {
            FindAVCDimensions(nal, width, height);
            ALOGI("dimensions %dx%d", *width, *height);
        }
    }

    *out++ = numPicParameterSets;

    for (size_t i = 0; i < numPicParameterSets; ++i) {
        sp<ABuffer> nal = paramSets.editItemAt(i + numSeqParameterSets);

        *out++ = nal->size() >> 8;
        *out++ = nal->size() & 0xff;

        memcpy(out, nal->data(), nal->size());

        out += nal->size();
    }

    // hexdump(csd->data(), csd->size());

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

    // hexdump(csd->data(), csd->size());

    return csd;
}

// From mpeg4-generic configuration data.
sp<ABuffer> MakeAACCodecSpecificData2(const char *params) {
    AString val;
    unsigned long objectType;
    if (GetAttribute(params, "objectType", &val)) {
        const char *s = val.c_str();
        char *end;
        objectType = strtoul(s, &end, 10);
        CHECK(end > s && *end == '\0');
    } else {
        objectType = 0x40;  // Audio ISO/IEC 14496-3
    }

    CHECK(GetAttribute(params, "config", &val));

    sp<ABuffer> config = decodeHex(val);
    CHECK(config != NULL);

    // Make sure size fits into a single byte and doesn't have to
    // be encoded.
    CHECK_LT(20 + config->size(), 128u);

    const uint8_t *data = config->data();

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

    sp<ABuffer> csd = new ABuffer(sizeof(kStaticESDS) + config->size());
    uint8_t *dst = csd->data();
    *dst++ = 0x03;
    *dst++ = 20 + config->size();
    *dst++ = 0x00;  // ES_ID
    *dst++ = 0x00;
    *dst++ = 0x00;  // streamDependenceFlag, URL_Flag, OCRstreamFlag
    *dst++ = 0x04;
    *dst++ = 15 + config->size();
    *dst++ = objectType;
    for (int i = 0; i < 12; ++i) { *dst++ = 0x00; }
    *dst++ = 0x05;
    *dst++ = config->size();
    memcpy(dst, config->data(), config->size());

    // hexdump(csd->data(), csd->size());

    return csd;
}

static size_t GetSizeWidth(size_t x) {
    size_t n = 1;
    while (x > 127) {
        ++n;
        x >>= 7;
    }
    return n;
}

static uint8_t *EncodeSize(uint8_t *dst, size_t x) {
    while (x > 127) {
        *dst++ = (x & 0x7f) | 0x80;
        x >>= 7;
    }
    *dst++ = x;
    return dst;
}

static bool ExtractDimensionsMPEG4Config(
        const sp<ABuffer> &config, int32_t *width, int32_t *height) {
    *width = 0;
    *height = 0;

    const uint8_t *ptr = config->data();
    size_t offset = 0;
    bool foundVOL = false;
    while (offset + 3 < config->size()) {
        if (memcmp("\x00\x00\x01", &ptr[offset], 3)
                || (ptr[offset + 3] & 0xf0) != 0x20) {
            ++offset;
            continue;
        }

        foundVOL = true;
        break;
    }

    if (!foundVOL) {
        return false;
    }

    return ExtractDimensionsFromVOLHeader(
            &ptr[offset], config->size() - offset, width, height);
}

static sp<ABuffer> MakeMPEG4VideoCodecSpecificData(
        const char *params, int32_t *width, int32_t *height) {
    *width = 0;
    *height = 0;

    AString val;
    CHECK(GetAttribute(params, "config", &val));

    sp<ABuffer> config = decodeHex(val);
    CHECK(config != NULL);

    if (!ExtractDimensionsMPEG4Config(config, width, height)) {
        return NULL;
    }

    ALOGI("VOL dimensions = %dx%d", *width, *height);

    size_t len1 = config->size() + GetSizeWidth(config->size()) + 1;
    size_t len2 = len1 + GetSizeWidth(len1) + 1 + 13;
    size_t len3 = len2 + GetSizeWidth(len2) + 1 + 3;

    sp<ABuffer> csd = new ABuffer(len3);
    uint8_t *dst = csd->data();
    *dst++ = 0x03;
    dst = EncodeSize(dst, len2 + 3);
    *dst++ = 0x00;  // ES_ID
    *dst++ = 0x00;
    *dst++ = 0x00;  // streamDependenceFlag, URL_Flag, OCRstreamFlag

    *dst++ = 0x04;
    dst = EncodeSize(dst, len1 + 13);
    *dst++ = 0x01;  // Video ISO/IEC 14496-2 Simple Profile
    for (size_t i = 0; i < 12; ++i) {
        *dst++ = 0x00;
    }

    *dst++ = 0x05;
    dst = EncodeSize(dst, config->size());
    memcpy(dst, config->data(), config->size());
    dst += config->size();

    // hexdump(csd->data(), csd->size());

    return csd;
}

APacketSource::APacketSource(
        const sp<ASessionDescription> &sessionDesc, size_t index)
    : mInitCheck(NO_INIT),
      mFormat(new MetaData) {
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
        if (!sessionDesc->getDimensions(index, PT, &width, &height)) {
            width = -1;
            height = -1;
        }

        int32_t encWidth, encHeight;
        sp<ABuffer> codecSpecificData =
            MakeAVCCodecSpecificData(params.c_str(), &encWidth, &encHeight);

        if (codecSpecificData != NULL) {
            if (width < 0) {
                // If no explicit width/height given in the sdp, use the dimensions
                // extracted from the first sequence parameter set.
                width = encWidth;
                height = encHeight;
            }

            mFormat->setData(
                    kKeyAVCC, 0,
                    codecSpecificData->data(), codecSpecificData->size());
        } else if (width < 0) {
            mInitCheck = ERROR_UNSUPPORTED;
            return;
        }

        mFormat->setInt32(kKeyWidth, width);
        mFormat->setInt32(kKeyHeight, height);
    } else if (!strncmp(desc.c_str(), "H263-2000/", 10)
            || !strncmp(desc.c_str(), "H263-1998/", 10)) {
        mFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_H263);

        int32_t width, height;
        if (!sessionDesc->getDimensions(index, PT, &width, &height)) {
            mInitCheck = ERROR_UNSUPPORTED;
            return;
        }

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
    } else if (!strncmp(desc.c_str(), "MP4V-ES/", 8)) {
        mFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_MPEG4);

        int32_t width, height;
        if (!sessionDesc->getDimensions(index, PT, &width, &height)) {
            width = -1;
            height = -1;
        }

        int32_t encWidth, encHeight;
        sp<ABuffer> codecSpecificData =
            MakeMPEG4VideoCodecSpecificData(
                    params.c_str(), &encWidth, &encHeight);

        if (codecSpecificData != NULL) {
            mFormat->setData(
                    kKeyESDS, 0,
                    codecSpecificData->data(), codecSpecificData->size());

            if (width < 0) {
                width = encWidth;
                height = encHeight;
            }
        } else if (width < 0) {
            mInitCheck = ERROR_UNSUPPORTED;
            return;
        }

        mFormat->setInt32(kKeyWidth, width);
        mFormat->setInt32(kKeyHeight, height);
    } else if (!strncasecmp(desc.c_str(), "mpeg4-generic/", 14)) {
        AString val;
        if (!GetAttribute(params.c_str(), "mode", &val)
                || (strcasecmp(val.c_str(), "AAC-lbr")
                    && strcasecmp(val.c_str(), "AAC-hbr"))) {
            mInitCheck = ERROR_UNSUPPORTED;
            return;
        }

        mFormat->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_AAC);

        int32_t sampleRate, numChannels;
        ASessionDescription::ParseFormatDesc(
                desc.c_str(), &sampleRate, &numChannels);

        mFormat->setInt32(kKeySampleRate, sampleRate);
        mFormat->setInt32(kKeyChannelCount, numChannels);

        sp<ABuffer> codecSpecificData =
            MakeAACCodecSpecificData2(params.c_str());

        mFormat->setData(
                kKeyESDS, 0,
                codecSpecificData->data(), codecSpecificData->size());
    } else if (ARawAudioAssembler::Supports(desc.c_str())) {
        ARawAudioAssembler::MakeFormat(desc.c_str(), mFormat);
    } else {
        mInitCheck = ERROR_UNSUPPORTED;
    }
}

APacketSource::~APacketSource() {
}

status_t APacketSource::initCheck() const {
    return mInitCheck;
}

sp<MetaData> APacketSource::getFormat() {
    return mFormat;
}

}  // namespace android
