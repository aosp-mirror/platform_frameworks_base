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
#define LOG_TAG "ESQueue"
#include <media/stagefright/foundation/ADebug.h>

#include "ESQueue.h"

#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/foundation/ABitReader.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MetaData.h>

#include "include/avc_utils.h"

namespace android {

ElementaryStreamQueue::ElementaryStreamQueue(Mode mode)
    : mMode(mode) {
}

sp<MetaData> ElementaryStreamQueue::getFormat() {
    return mFormat;
}

void ElementaryStreamQueue::clear() {
    mBuffer->setRange(0, 0);
    mTimestamps.clear();
    mFormat.clear();
}

status_t ElementaryStreamQueue::appendData(
        const void *data, size_t size, int64_t timeUs) {
    if (mBuffer == NULL || mBuffer->size() == 0) {
        switch (mMode) {
            case H264:
            {
                if (size < 4 || memcmp("\x00\x00\x00\x01", data, 4)) {
                    return ERROR_MALFORMED;
                }
                break;
            }

            case AAC:
            {
                uint8_t *ptr = (uint8_t *)data;

                if (size < 2 || ptr[0] != 0xff || (ptr[1] >> 4) != 0x0f) {
                    return ERROR_MALFORMED;
                }
                break;
            }

            default:
                TRESPASS();
                break;
        }
    }

    size_t neededSize = (mBuffer == NULL ? 0 : mBuffer->size()) + size;
    if (mBuffer == NULL || neededSize > mBuffer->capacity()) {
        neededSize = (neededSize + 65535) & ~65535;

        LOGV("resizing buffer to size %d", neededSize);

        sp<ABuffer> buffer = new ABuffer(neededSize);
        if (mBuffer != NULL) {
            memcpy(buffer->data(), mBuffer->data(), mBuffer->size());
            buffer->setRange(0, mBuffer->size());
        } else {
            buffer->setRange(0, 0);
        }

        mBuffer = buffer;
    }

    memcpy(mBuffer->data() + mBuffer->size(), data, size);
    mBuffer->setRange(0, mBuffer->size() + size);

    mTimestamps.push_back(timeUs);

    return OK;
}

sp<ABuffer> ElementaryStreamQueue::dequeueAccessUnit() {
    if (mMode == H264) {
        return dequeueAccessUnitH264();
    } else {
        CHECK_EQ((unsigned)mMode, (unsigned)AAC);
        return dequeueAccessUnitAAC();
    }
}

sp<ABuffer> ElementaryStreamQueue::dequeueAccessUnitAAC() {
    Vector<size_t> frameOffsets;
    Vector<size_t> frameSizes;
    size_t auSize = 0;

    size_t offset = 0;
    while (offset + 7 <= mBuffer->size()) {
        ABitReader bits(mBuffer->data() + offset, mBuffer->size() - offset);

        // adts_fixed_header

        CHECK_EQ(bits.getBits(12), 0xfffu);
        bits.skipBits(3);  // ID, layer
        bool protection_absent = bits.getBits(1) != 0;

        if (mFormat == NULL) {
            unsigned profile = bits.getBits(2);
            CHECK_NE(profile, 3u);
            unsigned sampling_freq_index = bits.getBits(4);
            bits.getBits(1);  // private_bit
            unsigned channel_configuration = bits.getBits(3);
            CHECK_NE(channel_configuration, 0u);
            bits.skipBits(2);  // original_copy, home

            mFormat = MakeAACCodecSpecificData(
                    profile, sampling_freq_index, channel_configuration);
        } else {
            // profile_ObjectType, sampling_frequency_index, private_bits,
            // channel_configuration, original_copy, home
            bits.skipBits(12);
        }

        // adts_variable_header

        // copyright_identification_bit, copyright_identification_start
        bits.skipBits(2);

        unsigned aac_frame_length = bits.getBits(13);

        bits.skipBits(11);  // adts_buffer_fullness

        unsigned number_of_raw_data_blocks_in_frame = bits.getBits(2);

        if (number_of_raw_data_blocks_in_frame != 0) {
            // To be implemented.
            TRESPASS();
        }

        if (offset + aac_frame_length > mBuffer->size()) {
            break;
        }

        size_t headerSize = protection_absent ? 7 : 9;

        frameOffsets.push(offset + headerSize);
        frameSizes.push(aac_frame_length - headerSize);
        auSize += aac_frame_length - headerSize;

        offset += aac_frame_length;
    }

    if (offset == 0) {
        return NULL;
    }

    sp<ABuffer> accessUnit = new ABuffer(auSize);
    size_t dstOffset = 0;
    for (size_t i = 0; i < frameOffsets.size(); ++i) {
        memcpy(accessUnit->data() + dstOffset,
               mBuffer->data() + frameOffsets.itemAt(i),
               frameSizes.itemAt(i));

        dstOffset += frameSizes.itemAt(i);
    }

    memmove(mBuffer->data(), mBuffer->data() + offset,
            mBuffer->size() - offset);
    mBuffer->setRange(0, mBuffer->size() - offset);

    CHECK_GT(mTimestamps.size(), 0u);
    int64_t timeUs = *mTimestamps.begin();
    mTimestamps.erase(mTimestamps.begin());

    accessUnit->meta()->setInt64("time", timeUs);

    return accessUnit;
}

// static
sp<MetaData> ElementaryStreamQueue::MakeAACCodecSpecificData(
        unsigned profile, unsigned sampling_freq_index,
        unsigned channel_configuration) {
    sp<MetaData> meta = new MetaData;
    meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_AAC);

    CHECK_LE(sampling_freq_index, 11u);
    static const int32_t kSamplingFreq[] = {
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
        16000, 12000, 11025, 8000
    };
    meta->setInt32(kKeySampleRate, kSamplingFreq[sampling_freq_index]);
    meta->setInt32(kKeyChannelCount, channel_configuration);

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

        // oooo offf fccc c000
        // o - audioObjectType
        // f - samplingFreqIndex
        // c - channelConfig
    };
    sp<ABuffer> csd = new ABuffer(sizeof(kStaticESDS) + 2);
    memcpy(csd->data(), kStaticESDS, sizeof(kStaticESDS));

    csd->data()[sizeof(kStaticESDS)] =
        ((profile + 1) << 3) | (sampling_freq_index >> 1);

    csd->data()[sizeof(kStaticESDS) + 1] =
        ((sampling_freq_index << 7) & 0x80) | (channel_configuration << 3);

    meta->setData(kKeyESDS, 0, csd->data(), csd->size());

    return meta;
}

struct NALPosition {
    size_t nalOffset;
    size_t nalSize;
};

sp<ABuffer> ElementaryStreamQueue::dequeueAccessUnitH264() {
    const uint8_t *data = mBuffer->data();
    size_t size = mBuffer->size();

    Vector<NALPosition> nals;

    size_t totalSize = 0;

    status_t err;
    const uint8_t *nalStart;
    size_t nalSize;
    bool foundSlice = false;
    while ((err = getNextNALUnit(&data, &size, &nalStart, &nalSize)) == OK) {
        CHECK_GT(nalSize, 0u);

        unsigned nalType = nalStart[0] & 0x1f;
        bool flush = false;

        if (nalType == 1 || nalType == 5) {
            if (foundSlice) {
                ABitReader br(nalStart + 1, nalSize);
                unsigned first_mb_in_slice = parseUE(&br);

                if (first_mb_in_slice == 0) {
                    // This slice starts a new frame.

                    flush = true;
                }
            }

            foundSlice = true;
        } else if ((nalType == 9 || nalType == 7) && foundSlice) {
            // Access unit delimiter and SPS will be associated with the
            // next frame.

            flush = true;
        }

        if (flush) {
            // The access unit will contain all nal units up to, but excluding
            // the current one, separated by 0x00 0x00 0x00 0x01 startcodes.

            size_t auSize = 4 * nals.size() + totalSize;
            sp<ABuffer> accessUnit = new ABuffer(auSize);

#if !LOG_NDEBUG
            AString out;
#endif

            size_t dstOffset = 0;
            for (size_t i = 0; i < nals.size(); ++i) {
                const NALPosition &pos = nals.itemAt(i);

                unsigned nalType = mBuffer->data()[pos.nalOffset] & 0x1f;

#if !LOG_NDEBUG
                char tmp[128];
                sprintf(tmp, "0x%02x", nalType);
                if (i > 0) {
                    out.append(", ");
                }
                out.append(tmp);
#endif

                memcpy(accessUnit->data() + dstOffset, "\x00\x00\x00\x01", 4);

                memcpy(accessUnit->data() + dstOffset + 4,
                       mBuffer->data() + pos.nalOffset,
                       pos.nalSize);

                dstOffset += pos.nalSize + 4;
            }

            LOGV("accessUnit contains nal types %s", out.c_str());

            const NALPosition &pos = nals.itemAt(nals.size() - 1);
            size_t nextScan = pos.nalOffset + pos.nalSize;

            memmove(mBuffer->data(),
                    mBuffer->data() + nextScan,
                    mBuffer->size() - nextScan);

            mBuffer->setRange(0, mBuffer->size() - nextScan);

            CHECK_GT(mTimestamps.size(), 0u);
            int64_t timeUs = *mTimestamps.begin();
            mTimestamps.erase(mTimestamps.begin());

            accessUnit->meta()->setInt64("time", timeUs);

            if (mFormat == NULL) {
                mFormat = MakeAVCCodecSpecificData(accessUnit);
            }

            return accessUnit;
        }

        NALPosition pos;
        pos.nalOffset = nalStart - mBuffer->data();
        pos.nalSize = nalSize;

        nals.push(pos);

        totalSize += nalSize;
    }
    CHECK_EQ(err, (status_t)-EAGAIN);

    return NULL;
}

}  // namespace android
