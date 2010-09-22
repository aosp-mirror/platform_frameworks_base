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
#define LOG_TAG "ATSParser"
#include <utils/Log.h>

#include "ATSParser.h"

#include "AnotherPacketSource.h"
#include "include/avc_utils.h"

#include <media/stagefright/foundation/ABitReader.h>
#include <media/stagefright/foundation/ABuffer.h>
#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/foundation/AMessage.h>
#include <media/stagefright/foundation/hexdump.h>
#include <media/stagefright/MediaDefs.h>
#include <media/stagefright/MediaErrors.h>
#include <media/stagefright/MetaData.h>
#include <utils/KeyedVector.h>

namespace android {

// I want the expression "y" evaluated even if verbose logging is off.
#define MY_LOGV(x, y) \
    do { unsigned tmp = y; LOGV(x, tmp); } while (0)

static const size_t kTSPacketSize = 188;

struct ATSParser::Program : public RefBase {
    Program(unsigned programMapPID);

    bool parsePID(
            unsigned pid, unsigned payload_unit_start_indicator,
            ABitReader *br);

    sp<MediaSource> getSource(SourceType type);

private:
    unsigned mProgramMapPID;
    KeyedVector<unsigned, sp<Stream> > mStreams;

    void parseProgramMap(ABitReader *br);

    DISALLOW_EVIL_CONSTRUCTORS(Program);
};

struct ATSParser::Stream : public RefBase {
    Stream(unsigned elementaryPID, unsigned streamType);

    void parse(
            unsigned payload_unit_start_indicator,
            ABitReader *br);

    sp<MediaSource> getSource(SourceType type);

protected:
    virtual ~Stream();

private:
    unsigned mElementaryPID;
    unsigned mStreamType;

    sp<ABuffer> mBuffer;
    sp<AnotherPacketSource> mSource;
    bool mPayloadStarted;

    void flush();
    void parsePES(ABitReader *br);

    void onPayloadData(
            unsigned PTS_DTS_flags, uint64_t PTS, uint64_t DTS,
            const uint8_t *data, size_t size);

    void extractAACFrames(const sp<ABuffer> &buffer);

    DISALLOW_EVIL_CONSTRUCTORS(Stream);
};

////////////////////////////////////////////////////////////////////////////////

ATSParser::Program::Program(unsigned programMapPID)
    : mProgramMapPID(programMapPID) {
}

bool ATSParser::Program::parsePID(
        unsigned pid, unsigned payload_unit_start_indicator,
        ABitReader *br) {
    if (pid == mProgramMapPID) {
        if (payload_unit_start_indicator) {
            unsigned skip = br->getBits(8);
            br->skipBits(skip * 8);
        }

        parseProgramMap(br);
        return true;
    }

    ssize_t index = mStreams.indexOfKey(pid);
    if (index < 0) {
        return false;
    }

    mStreams.editValueAt(index)->parse(
            payload_unit_start_indicator, br);

    return true;
}

void ATSParser::Program::parseProgramMap(ABitReader *br) {
    unsigned table_id = br->getBits(8);
    LOGV("  table_id = %u", table_id);
    CHECK_EQ(table_id, 0x02u);

    unsigned section_syntax_indicator = br->getBits(1);
    LOGV("  section_syntax_indicator = %u", section_syntax_indicator);
    CHECK_EQ(section_syntax_indicator, 1u);

    CHECK_EQ(br->getBits(1), 0u);
    MY_LOGV("  reserved = %u", br->getBits(2));

    unsigned section_length = br->getBits(12);
    LOGV("  section_length = %u", section_length);
    CHECK((section_length & 0xc00) == 0);
    CHECK_LE(section_length, 1021u);

    MY_LOGV("  program_number = %u", br->getBits(16));
    MY_LOGV("  reserved = %u", br->getBits(2));
    MY_LOGV("  version_number = %u", br->getBits(5));
    MY_LOGV("  current_next_indicator = %u", br->getBits(1));
    MY_LOGV("  section_number = %u", br->getBits(8));
    MY_LOGV("  last_section_number = %u", br->getBits(8));
    MY_LOGV("  reserved = %u", br->getBits(3));
    MY_LOGV("  PCR_PID = 0x%04x", br->getBits(13));
    MY_LOGV("  reserved = %u", br->getBits(4));

    unsigned program_info_length = br->getBits(12);
    LOGV("  program_info_length = %u", program_info_length);
    CHECK((program_info_length & 0xc00) == 0);

    br->skipBits(program_info_length * 8);  // skip descriptors

    // infoBytesRemaining is the number of bytes that make up the
    // variable length section of ES_infos. It does not include the
    // final CRC.
    size_t infoBytesRemaining = section_length - 9 - program_info_length - 4;

    while (infoBytesRemaining > 0) {
        CHECK_GE(infoBytesRemaining, 5u);

        unsigned streamType = br->getBits(8);
        LOGV("    stream_type = 0x%02x", streamType);

        MY_LOGV("    reserved = %u", br->getBits(3));

        unsigned elementaryPID = br->getBits(13);
        LOGV("    elementary_PID = 0x%04x", elementaryPID);

        MY_LOGV("    reserved = %u", br->getBits(4));

        unsigned ES_info_length = br->getBits(12);
        LOGV("    ES_info_length = %u", ES_info_length);
        CHECK((ES_info_length & 0xc00) == 0);

        CHECK_GE(infoBytesRemaining - 5, ES_info_length);

#if 0
        br->skipBits(ES_info_length * 8);  // skip descriptors
#else
        unsigned info_bytes_remaining = ES_info_length;
        while (info_bytes_remaining >= 2) {
            MY_LOGV("      tag = 0x%02x", br->getBits(8));

            unsigned descLength = br->getBits(8);
            LOGV("      len = %u", descLength);

            CHECK_GE(info_bytes_remaining, 2 + descLength);

            br->skipBits(descLength * 8);

            info_bytes_remaining -= descLength + 2;
        }
        CHECK_EQ(info_bytes_remaining, 0u);
#endif

        ssize_t index = mStreams.indexOfKey(elementaryPID);
#if 0  // XXX revisit
        CHECK_LT(index, 0);
        mStreams.add(elementaryPID, new Stream(elementaryPID, streamType));
#else
        if (index < 0) {
            mStreams.add(elementaryPID, new Stream(elementaryPID, streamType));
        }
#endif

        infoBytesRemaining -= 5 + ES_info_length;
    }

    CHECK_EQ(infoBytesRemaining, 0u);

    MY_LOGV("  CRC = 0x%08x", br->getBits(32));
}

sp<MediaSource> ATSParser::Program::getSource(SourceType type) {
    for (size_t i = 0; i < mStreams.size(); ++i) {
        sp<MediaSource> source = mStreams.editValueAt(i)->getSource(type);
        if (source != NULL) {
            return source;
        }
    }

    return NULL;
}

////////////////////////////////////////////////////////////////////////////////

ATSParser::Stream::Stream(unsigned elementaryPID, unsigned streamType)
    : mElementaryPID(elementaryPID),
      mStreamType(streamType),
      mBuffer(new ABuffer(128 * 1024)),
      mPayloadStarted(false) {
    mBuffer->setRange(0, 0);
}

ATSParser::Stream::~Stream() {
}

void ATSParser::Stream::parse(
        unsigned payload_unit_start_indicator, ABitReader *br) {
    if (payload_unit_start_indicator) {
        if (mPayloadStarted) {
            // Otherwise we run the danger of receiving the trailing bytes
            // of a PES packet that we never saw the start of and assuming
            // we have a a complete PES packet.

            flush();
        }

        mPayloadStarted = true;
    }

    if (!mPayloadStarted) {
        return;
    }

    size_t payloadSizeBits = br->numBitsLeft();
    CHECK((payloadSizeBits % 8) == 0);

    CHECK_LE(mBuffer->size() + payloadSizeBits / 8, mBuffer->capacity());

    memcpy(mBuffer->data() + mBuffer->size(), br->data(), payloadSizeBits / 8);
    mBuffer->setRange(0, mBuffer->size() + payloadSizeBits / 8);
}

void ATSParser::Stream::parsePES(ABitReader *br) {
    unsigned packet_startcode_prefix = br->getBits(24);

    LOGV("packet_startcode_prefix = 0x%08x", packet_startcode_prefix);

    CHECK_EQ(packet_startcode_prefix, 0x000001u);

    unsigned stream_id = br->getBits(8);
    LOGV("stream_id = 0x%02x", stream_id);

    unsigned PES_packet_length = br->getBits(16);
    LOGV("PES_packet_length = %u", PES_packet_length);

    if (stream_id != 0xbc  // program_stream_map
            && stream_id != 0xbe  // padding_stream
            && stream_id != 0xbf  // private_stream_2
            && stream_id != 0xf0  // ECM
            && stream_id != 0xf1  // EMM
            && stream_id != 0xff  // program_stream_directory
            && stream_id != 0xf2  // DSMCC
            && stream_id != 0xf8) {  // H.222.1 type E
        CHECK_EQ(br->getBits(2), 2u);

        MY_LOGV("PES_scrambling_control = %u", br->getBits(2));
        MY_LOGV("PES_priority = %u", br->getBits(1));
        MY_LOGV("data_alignment_indicator = %u", br->getBits(1));
        MY_LOGV("copyright = %u", br->getBits(1));
        MY_LOGV("original_or_copy = %u", br->getBits(1));

        unsigned PTS_DTS_flags = br->getBits(2);
        LOGV("PTS_DTS_flags = %u", PTS_DTS_flags);

        unsigned ESCR_flag = br->getBits(1);
        LOGV("ESCR_flag = %u", ESCR_flag);

        unsigned ES_rate_flag = br->getBits(1);
        LOGV("ES_rate_flag = %u", ES_rate_flag);

        unsigned DSM_trick_mode_flag = br->getBits(1);
        LOGV("DSM_trick_mode_flag = %u", DSM_trick_mode_flag);

        unsigned additional_copy_info_flag = br->getBits(1);
        LOGV("additional_copy_info_flag = %u", additional_copy_info_flag);

        MY_LOGV("PES_CRC_flag = %u", br->getBits(1));
        MY_LOGV("PES_extension_flag = %u", br->getBits(1));

        unsigned PES_header_data_length = br->getBits(8);
        LOGV("PES_header_data_length = %u", PES_header_data_length);

        unsigned optional_bytes_remaining = PES_header_data_length;

        uint64_t PTS = 0, DTS = 0;

        if (PTS_DTS_flags == 2 || PTS_DTS_flags == 3) {
            CHECK_GE(optional_bytes_remaining, 5u);

            CHECK_EQ(br->getBits(4), PTS_DTS_flags);

            PTS = ((uint64_t)br->getBits(3)) << 30;
            CHECK_EQ(br->getBits(1), 1u);
            PTS |= ((uint64_t)br->getBits(15)) << 15;
            CHECK_EQ(br->getBits(1), 1u);
            PTS |= br->getBits(15);
            CHECK_EQ(br->getBits(1), 1u);

            LOGV("PTS = %llu", PTS);
            // LOGI("PTS = %.2f secs", PTS / 90000.0f);

            optional_bytes_remaining -= 5;

            if (PTS_DTS_flags == 3) {
                CHECK_GE(optional_bytes_remaining, 5u);

                CHECK_EQ(br->getBits(4), 1u);

                DTS = ((uint64_t)br->getBits(3)) << 30;
                CHECK_EQ(br->getBits(1), 1u);
                DTS |= ((uint64_t)br->getBits(15)) << 15;
                CHECK_EQ(br->getBits(1), 1u);
                DTS |= br->getBits(15);
                CHECK_EQ(br->getBits(1), 1u);

                LOGV("DTS = %llu", DTS);

                optional_bytes_remaining -= 5;
            }
        }

        if (ESCR_flag) {
            CHECK_GE(optional_bytes_remaining, 6u);

            br->getBits(2);

            uint64_t ESCR = ((uint64_t)br->getBits(3)) << 30;
            CHECK_EQ(br->getBits(1), 1u);
            ESCR |= ((uint64_t)br->getBits(15)) << 15;
            CHECK_EQ(br->getBits(1), 1u);
            ESCR |= br->getBits(15);
            CHECK_EQ(br->getBits(1), 1u);

            LOGV("ESCR = %llu", ESCR);
            MY_LOGV("ESCR_extension = %u", br->getBits(9));

            CHECK_EQ(br->getBits(1), 1u);

            optional_bytes_remaining -= 6;
        }

        if (ES_rate_flag) {
            CHECK_GE(optional_bytes_remaining, 3u);

            CHECK_EQ(br->getBits(1), 1u);
            MY_LOGV("ES_rate = %u", br->getBits(22));
            CHECK_EQ(br->getBits(1), 1u);

            optional_bytes_remaining -= 3;
        }

        br->skipBits(optional_bytes_remaining * 8);

        // ES data follows.

        onPayloadData(
                PTS_DTS_flags, PTS, DTS,
                br->data(), br->numBitsLeft() / 8);

        if (PES_packet_length != 0) {
            CHECK_GE(PES_packet_length, PES_header_data_length + 3);

            unsigned dataLength =
                PES_packet_length - 3 - PES_header_data_length;

            CHECK_EQ(br->numBitsLeft(), dataLength * 8);

            br->skipBits(dataLength * 8);
        } else {
            size_t payloadSizeBits = br->numBitsLeft();
            CHECK((payloadSizeBits % 8) == 0);

            LOGV("There's %d bytes of payload.", payloadSizeBits / 8);
        }
    } else if (stream_id == 0xbe) {  // padding_stream
        CHECK_NE(PES_packet_length, 0u);
        br->skipBits(PES_packet_length * 8);
    } else {
        CHECK_NE(PES_packet_length, 0u);
        br->skipBits(PES_packet_length * 8);
    }
}

void ATSParser::Stream::flush() {
    if (mBuffer->size() == 0) {
        return;
    }

    LOGV("flushing stream 0x%04x size = %d", mElementaryPID, mBuffer->size());

    ABitReader br(mBuffer->data(), mBuffer->size());
    parsePES(&br);

    mBuffer->setRange(0, 0);
}

static sp<ABuffer> FindNAL(
        const uint8_t *data, size_t size, unsigned nalType,
        size_t *stopOffset) {
    bool foundStart = false;
    size_t startOffset = 0;

    size_t offset = 0;
    for (;;) {
        while (offset + 3 < size
                && memcmp("\x00\x00\x00\x01", &data[offset], 4)) {
            ++offset;
        }

        if (foundStart) {
            size_t nalSize;
            if (offset + 3 >= size) {
                nalSize = size - startOffset;
            } else {
                nalSize = offset - startOffset;
            }

            sp<ABuffer> nal = new ABuffer(nalSize);
            memcpy(nal->data(), &data[startOffset], nalSize);

            if (stopOffset != NULL) {
                *stopOffset = startOffset + nalSize;
            }

            return nal;
        }

        if (offset + 4 >= size) {
            return NULL;
        }

        if ((data[offset + 4] & 0x1f) == nalType) {
            foundStart = true;
            startOffset = offset + 4;
        }

        offset += 4;
    }
}

static sp<ABuffer> MakeAVCCodecSpecificData(
        const sp<ABuffer> &buffer, int32_t *width, int32_t *height) {
    const uint8_t *data = buffer->data();
    size_t size = buffer->size();

    sp<ABuffer> seqParamSet = FindNAL(data, size, 7, NULL);
    if (seqParamSet == NULL) {
        return NULL;
    }

    FindAVCDimensions(seqParamSet, width, height);

    size_t stopOffset;
    sp<ABuffer> picParamSet = FindNAL(data, size, 8, &stopOffset);
    CHECK(picParamSet != NULL);

    buffer->setRange(stopOffset, size - stopOffset);
    LOGI("buffer has %d bytes left.", buffer->size());

    size_t csdSize =
        1 + 3 + 1 + 1
        + 2 * 1 + seqParamSet->size()
        + 1 + 2 * 1 + picParamSet->size();

    sp<ABuffer> csd = new ABuffer(csdSize);
    uint8_t *out = csd->data();

    *out++ = 0x01;  // configurationVersion
    memcpy(out, seqParamSet->data() + 1, 3);  // profile/level...
    out += 3;
    *out++ = (0x3f << 2) | 1;  // lengthSize == 2 bytes
    *out++ = 0xe0 | 1;

    *out++ = seqParamSet->size() >> 8;
    *out++ = seqParamSet->size() & 0xff;
    memcpy(out, seqParamSet->data(), seqParamSet->size());
    out += seqParamSet->size();

    *out++ = 1;

    *out++ = picParamSet->size() >> 8;
    *out++ = picParamSet->size() & 0xff;
    memcpy(out, picParamSet->data(), picParamSet->size());

    return csd;
}

static bool getNextNALUnit(
        const uint8_t **_data, size_t *_size,
        const uint8_t **nalStart, size_t *nalSize) {
    const uint8_t *data = *_data;
    size_t size = *_size;

    *nalStart = NULL;
    *nalSize = 0;

    if (size == 0) {
        return false;
    }

    size_t offset = 0;
    for (;;) {
        CHECK_LT(offset + 2, size);

        if (!memcmp("\x00\x00\x01", &data[offset], 3)) {
            break;
        }

        CHECK_EQ((unsigned)data[offset], 0x00u);
        ++offset;
    }

    offset += 3;
    size_t startOffset = offset;

    while (offset + 2 < size
            && memcmp("\x00\x00\x00", &data[offset], 3)
            && memcmp("\x00\x00\x01", &data[offset], 3)) {
        ++offset;
    }

    if (offset + 2 >= size) {
        *nalStart = &data[startOffset];
        *nalSize = size - startOffset;

        *_data = NULL;
        *_size = 0;

        return true;
    }

    size_t endOffset = offset;

    while (offset + 2 < size && memcmp("\x00\x00\x01", &data[offset], 3)) {
        CHECK_EQ((unsigned)data[offset], 0x00u);
        ++offset;
    }

    CHECK_LT(offset + 2, size);

    *nalStart = &data[startOffset];
    *nalSize = endOffset - startOffset;

    *_data = &data[offset];
    *_size = size - offset;

    return true;
}

sp<ABuffer> MakeCleanAVCData(const uint8_t *data, size_t size) {
    const uint8_t *tmpData = data;
    size_t tmpSize = size;

    size_t totalSize = 0;
    const uint8_t *nalStart;
    size_t nalSize;
    while (getNextNALUnit(&tmpData, &tmpSize, &nalStart, &nalSize)) {
        totalSize += 4 + nalSize;
    }

    sp<ABuffer> buffer = new ABuffer(totalSize);
    size_t offset = 0;
    while (getNextNALUnit(&data, &size, &nalStart, &nalSize)) {
        memcpy(buffer->data() + offset, "\x00\x00\x00\x01", 4);
        memcpy(buffer->data() + offset + 4, nalStart, nalSize);

        offset += 4 + nalSize;
    }

    return buffer;
}

static sp<ABuffer> FindMPEG2ADTSConfig(
        const sp<ABuffer> &buffer, int32_t *sampleRate, int32_t *channelCount) {
    ABitReader br(buffer->data(), buffer->size());

    CHECK_EQ(br.getBits(12), 0xfffu);
    CHECK_EQ(br.getBits(1), 0u);
    CHECK_EQ(br.getBits(2), 0u);
    br.getBits(1);  // protection_absent
    unsigned profile = br.getBits(2);
    LOGI("profile = %u", profile);
    CHECK_NE(profile, 3u);
    unsigned sampling_freq_index = br.getBits(4);
    br.getBits(1);  // private_bit
    unsigned channel_configuration = br.getBits(3);
    CHECK_NE(channel_configuration, 0u);

    LOGI("sampling_freq_index = %u", sampling_freq_index);
    LOGI("channel_configuration = %u", channel_configuration);

    CHECK_LE(sampling_freq_index, 11u);
    static const int32_t kSamplingFreq[] = {
        96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050,
        16000, 12000, 11025, 8000
    };
    *sampleRate = kSamplingFreq[sampling_freq_index];

    *channelCount = channel_configuration;

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

    // hexdump(csd->data(), csd->size());
    return csd;
}

void ATSParser::Stream::onPayloadData(
        unsigned PTS_DTS_flags, uint64_t PTS, uint64_t DTS,
        const uint8_t *data, size_t size) {
    LOGV("onPayloadData mStreamType=0x%02x", mStreamType);

    sp<ABuffer> buffer;

    if (mStreamType == 0x1b) {
        buffer = MakeCleanAVCData(data, size);
    } else {
        // hexdump(data, size);

        buffer = new ABuffer(size);
        memcpy(buffer->data(), data, size);
    }

    if (mSource == NULL) {
        sp<MetaData> meta = new MetaData;

        if (mStreamType == 0x1b) {
            meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_VIDEO_AVC);

            int32_t width, height;
            sp<ABuffer> csd = MakeAVCCodecSpecificData(buffer, &width, &height);

            if (csd == NULL) {
                return;
            }

            meta->setData(kKeyAVCC, 0, csd->data(), csd->size());
            meta->setInt32(kKeyWidth, width);
            meta->setInt32(kKeyHeight, height);
        } else {
            CHECK_EQ(mStreamType, 0x0fu);

            meta->setCString(kKeyMIMEType, MEDIA_MIMETYPE_AUDIO_AAC);

            int32_t sampleRate, channelCount;
            sp<ABuffer> csd =
                FindMPEG2ADTSConfig(buffer, &sampleRate, &channelCount);

            LOGI("sampleRate = %d", sampleRate);
            LOGI("channelCount = %d", channelCount);

            meta->setInt32(kKeySampleRate, sampleRate);
            meta->setInt32(kKeyChannelCount, channelCount);

            meta->setData(kKeyESDS, 0, csd->data(), csd->size());
        }

        LOGI("created source!");
        mSource = new AnotherPacketSource(meta);

        // fall through
    }

    CHECK(PTS_DTS_flags == 2 || PTS_DTS_flags == 3);
    buffer->meta()->setInt64("time", (PTS * 100) / 9);

    if (mStreamType == 0x0f) {
        extractAACFrames(buffer);
    }

    mSource->queueAccessUnit(buffer);
}

// Disassemble one or more ADTS frames into their constituent parts and
// leave only the concatenated raw_data_blocks in the buffer.
void ATSParser::Stream::extractAACFrames(const sp<ABuffer> &buffer) {
    size_t dstOffset = 0;

    size_t offset = 0;
    while (offset < buffer->size()) {
        CHECK_LE(offset + 7, buffer->size());

        ABitReader bits(buffer->data() + offset, buffer->size() - offset);

        // adts_fixed_header

        CHECK_EQ(bits.getBits(12), 0xfffu);
        bits.skipBits(3);  // ID, layer
        bool protection_absent = bits.getBits(1) != 0;

        // profile_ObjectType, sampling_frequency_index, private_bits,
        // channel_configuration, original_copy, home
        bits.skipBits(12);

        // adts_variable_header

        // copyright_identification_bit, copyright_identification_start
        bits.skipBits(2);

        unsigned aac_frame_length = bits.getBits(13);

        bits.skipBits(11);  // adts_buffer_fullness

        unsigned number_of_raw_data_blocks_in_frame = bits.getBits(2);

        if (number_of_raw_data_blocks_in_frame == 0) {
            size_t scan = offset + aac_frame_length;

            offset += 7;
            if (!protection_absent) {
                offset += 2;
            }

            CHECK_LE(scan, buffer->size());

            LOGV("found aac raw data block at [0x%08x ; 0x%08x)", offset, scan);

            memmove(&buffer->data()[dstOffset], &buffer->data()[offset],
                    scan - offset);

            dstOffset += scan - offset;
            offset = scan;
        } else {
            // To be implemented.
            TRESPASS();
        }
    }
    CHECK_EQ(offset, buffer->size());

    buffer->setRange(buffer->offset(), dstOffset);
}

sp<MediaSource> ATSParser::Stream::getSource(SourceType type) {
    if ((type == AVC_VIDEO && mStreamType == 0x1b)
        || (type == MPEG2ADTS_AUDIO && mStreamType == 0x0f)) {
        return mSource;
    }

    return NULL;
}

////////////////////////////////////////////////////////////////////////////////

ATSParser::ATSParser() {
}

ATSParser::~ATSParser() {
}

void ATSParser::feedTSPacket(const void *data, size_t size) {
    CHECK_EQ(size, kTSPacketSize);

    ABitReader br((const uint8_t *)data, kTSPacketSize);
    parseTS(&br);
}

void ATSParser::parseProgramAssociationTable(ABitReader *br) {
    unsigned table_id = br->getBits(8);
    LOGV("  table_id = %u", table_id);
    CHECK_EQ(table_id, 0x00u);

    unsigned section_syntax_indictor = br->getBits(1);
    LOGV("  section_syntax_indictor = %u", section_syntax_indictor);
    CHECK_EQ(section_syntax_indictor, 1u);

    CHECK_EQ(br->getBits(1), 0u);
    MY_LOGV("  reserved = %u", br->getBits(2));

    unsigned section_length = br->getBits(12);
    LOGV("  section_length = %u", section_length);
    CHECK((section_length & 0xc00) == 0);

    MY_LOGV("  transport_stream_id = %u", br->getBits(16));
    MY_LOGV("  reserved = %u", br->getBits(2));
    MY_LOGV("  version_number = %u", br->getBits(5));
    MY_LOGV("  current_next_indicator = %u", br->getBits(1));
    MY_LOGV("  section_number = %u", br->getBits(8));
    MY_LOGV("  last_section_number = %u", br->getBits(8));

    size_t numProgramBytes = (section_length - 5 /* header */ - 4 /* crc */);
    CHECK_EQ((numProgramBytes % 4), 0u);

    for (size_t i = 0; i < numProgramBytes / 4; ++i) {
        unsigned program_number = br->getBits(16);
        LOGV("    program_number = %u", program_number);

        MY_LOGV("    reserved = %u", br->getBits(3));

        if (program_number == 0) {
            MY_LOGV("    network_PID = 0x%04x", br->getBits(13));
        } else {
            unsigned programMapPID = br->getBits(13);

            LOGV("    program_map_PID = 0x%04x", programMapPID);

            mPrograms.push(new Program(programMapPID));
        }
    }

    MY_LOGV("  CRC = 0x%08x", br->getBits(32));
}

void ATSParser::parsePID(
        ABitReader *br, unsigned PID,
        unsigned payload_unit_start_indicator) {
    if (PID == 0) {
        if (payload_unit_start_indicator) {
            unsigned skip = br->getBits(8);
            br->skipBits(skip * 8);
        }
        parseProgramAssociationTable(br);
        return;
    }

    bool handled = false;
    for (size_t i = 0; i < mPrograms.size(); ++i) {
        if (mPrograms.editItemAt(i)->parsePID(
                    PID, payload_unit_start_indicator, br)) {
            handled = true;
            break;
        }
    }

    if (!handled) {
        LOGV("PID 0x%04x not handled.", PID);
    }
}

void ATSParser::parseAdaptationField(ABitReader *br) {
    unsigned adaptation_field_length = br->getBits(8);
    if (adaptation_field_length > 0) {
        br->skipBits(adaptation_field_length * 8);  // XXX
    }
}

void ATSParser::parseTS(ABitReader *br) {
    LOGV("---");

    unsigned sync_byte = br->getBits(8);
    CHECK_EQ(sync_byte, 0x47u);

    MY_LOGV("transport_error_indicator = %u", br->getBits(1));

    unsigned payload_unit_start_indicator = br->getBits(1);
    LOGV("payload_unit_start_indicator = %u", payload_unit_start_indicator);

    MY_LOGV("transport_priority = %u", br->getBits(1));

    unsigned PID = br->getBits(13);
    LOGV("PID = 0x%04x", PID);

    MY_LOGV("transport_scrambling_control = %u", br->getBits(2));

    unsigned adaptation_field_control = br->getBits(2);
    LOGV("adaptation_field_control = %u", adaptation_field_control);

    MY_LOGV("continuity_counter = %u", br->getBits(4));

    if (adaptation_field_control == 2 || adaptation_field_control == 3) {
        parseAdaptationField(br);
    }

    if (adaptation_field_control == 1 || adaptation_field_control == 3) {
        parsePID(br, PID, payload_unit_start_indicator);
    }
}

sp<MediaSource> ATSParser::getSource(SourceType type) {
    for (size_t i = 0; i < mPrograms.size(); ++i) {
        sp<MediaSource> source = mPrograms.editItemAt(i)->getSource(type);

        if (source != NULL) {
            return source;
        }
    }

    return NULL;
}

}  // namespace android
