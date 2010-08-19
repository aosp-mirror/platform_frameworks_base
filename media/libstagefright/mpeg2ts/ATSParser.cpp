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
    LOG(VERBOSE) << "  table_id = " << table_id;
    CHECK_EQ(table_id, 0x02u);

    unsigned section_syntax_indictor = br->getBits(1);
    LOG(VERBOSE) << "  section_syntax_indictor = " << section_syntax_indictor;
    CHECK_EQ(section_syntax_indictor, 1u);

    CHECK_EQ(br->getBits(1), 0u);
    LOG(VERBOSE) << "  reserved = " << br->getBits(2);

    unsigned section_length = br->getBits(12);
    LOG(VERBOSE) << "  section_length = " << section_length;
    CHECK((section_length & 0xc00) == 0);
    CHECK_LE(section_length, 1021u);

    LOG(VERBOSE) << "  program_number = " << br->getBits(16);
    LOG(VERBOSE) << "  reserved = " << br->getBits(2);
    LOG(VERBOSE) << "  version_number = " << br->getBits(5);
    LOG(VERBOSE) << "  current_next_indicator = " << br->getBits(1);
    LOG(VERBOSE) << "  section_number = " << br->getBits(8);
    LOG(VERBOSE) << "  last_section_number = " << br->getBits(8);
    LOG(VERBOSE) << "  reserved = " << br->getBits(3);

    LOG(VERBOSE) << "  PCR_PID = "
              << StringPrintf("0x%04x", br->getBits(13));

    LOG(VERBOSE) << "  reserved = " << br->getBits(4);

    unsigned program_info_length = br->getBits(12);
    LOG(VERBOSE) << "  program_info_length = " << program_info_length;
    CHECK((program_info_length & 0xc00) == 0);

    br->skipBits(program_info_length * 8);  // skip descriptors

    // infoBytesRemaining is the number of bytes that make up the
    // variable length section of ES_infos. It does not include the
    // final CRC.
    size_t infoBytesRemaining = section_length - 9 - program_info_length - 4;

    while (infoBytesRemaining > 0) {
        CHECK_GE(infoBytesRemaining, 5u);

        unsigned streamType = br->getBits(8);
        LOG(VERBOSE) << "    stream_type = "
                  << StringPrintf("0x%02x", streamType);

        LOG(VERBOSE) << "    reserved = " << br->getBits(3);

        unsigned elementaryPID = br->getBits(13);
        LOG(VERBOSE) << "    elementary_PID = "
                  << StringPrintf("0x%04x", elementaryPID);

        LOG(VERBOSE) << "    reserved = " << br->getBits(4);

        unsigned ES_info_length = br->getBits(12);
        LOG(VERBOSE) << "    ES_info_length = " << ES_info_length;
        CHECK((ES_info_length & 0xc00) == 0);

        CHECK_GE(infoBytesRemaining - 5, ES_info_length);

#if 0
        br->skipBits(ES_info_length * 8);  // skip descriptors
#else
        unsigned info_bytes_remaining = ES_info_length;
        while (info_bytes_remaining >= 2) {
            LOG(VERBOSE) << "      tag = " << StringPrintf("0x%02x", br->getBits(8));

            unsigned descLength = br->getBits(8);
            LOG(VERBOSE) << "      len = " << descLength;

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

    LOG(VERBOSE) << "  CRC = " << StringPrintf("0x%08x", br->getBits(32));
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
      mBuffer(new ABuffer(65536)),
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
    CHECK_EQ(payloadSizeBits % 8, 0u);

    CHECK_LE(mBuffer->size() + payloadSizeBits / 8, mBuffer->capacity());

    memcpy(mBuffer->data() + mBuffer->size(), br->data(), payloadSizeBits / 8);
    mBuffer->setRange(0, mBuffer->size() + payloadSizeBits / 8);
}

void ATSParser::Stream::parsePES(ABitReader *br) {
    unsigned packet_startcode_prefix = br->getBits(24);

    LOG(VERBOSE) << "packet_startcode_prefix = "
              << StringPrintf("0x%08x", packet_startcode_prefix);

    CHECK_EQ(packet_startcode_prefix, 0x000001u);

    unsigned stream_id = br->getBits(8);
    LOG(VERBOSE) << "stream_id = " << StringPrintf("0x%02x", stream_id);

    unsigned PES_packet_length = br->getBits(16);
    LOG(VERBOSE) << "PES_packet_length = " << PES_packet_length;

    if (stream_id != 0xbc  // program_stream_map
            && stream_id != 0xbe  // padding_stream
            && stream_id != 0xbf  // private_stream_2
            && stream_id != 0xf0  // ECM
            && stream_id != 0xf1  // EMM
            && stream_id != 0xff  // program_stream_directory
            && stream_id != 0xf2  // DSMCC
            && stream_id != 0xf8) {  // H.222.1 type E
        CHECK_EQ(br->getBits(2), 2u);

        LOG(VERBOSE) << "PES_scrambling_control = " << br->getBits(2);
        LOG(VERBOSE) << "PES_priority = " << br->getBits(1);
        LOG(VERBOSE) << "data_alignment_indicator = " << br->getBits(1);
        LOG(VERBOSE) << "copyright = " << br->getBits(1);
        LOG(VERBOSE) << "original_or_copy = " << br->getBits(1);

        unsigned PTS_DTS_flags = br->getBits(2);
        LOG(VERBOSE) << "PTS_DTS_flags = " << PTS_DTS_flags;

        unsigned ESCR_flag = br->getBits(1);
        LOG(VERBOSE) << "ESCR_flag = " << ESCR_flag;

        unsigned ES_rate_flag = br->getBits(1);
        LOG(VERBOSE) << "ES_rate_flag = " << ES_rate_flag;

        unsigned DSM_trick_mode_flag = br->getBits(1);
        LOG(VERBOSE) << "DSM_trick_mode_flag = " << DSM_trick_mode_flag;

        unsigned additional_copy_info_flag = br->getBits(1);
        LOG(VERBOSE) << "additional_copy_info_flag = "
                  << additional_copy_info_flag;

        LOG(VERBOSE) << "PES_CRC_flag = " << br->getBits(1);
        LOG(VERBOSE) << "PES_extension_flag = " << br->getBits(1);

        unsigned PES_header_data_length = br->getBits(8);
        LOG(VERBOSE) << "PES_header_data_length = " << PES_header_data_length;

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

            LOG(VERBOSE) << "PTS = " << PTS;
            // LOG(INFO) << "PTS = " << PTS / 90000.0f << " secs";

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

                LOG(VERBOSE) << "DTS = " << DTS;

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

            LOG(VERBOSE) << "ESCR = " << ESCR;
            LOG(VERBOSE) << "ESCR_extension = " << br->getBits(9);

            CHECK_EQ(br->getBits(1), 1u);

            optional_bytes_remaining -= 6;
        }

        if (ES_rate_flag) {
            CHECK_GE(optional_bytes_remaining, 3u);

            CHECK_EQ(br->getBits(1), 1u);
            LOG(VERBOSE) << "ES_rate = " << br->getBits(22);
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

            LOG(VERBOSE) << "There's " << (payloadSizeBits / 8)
                         << " bytes of payload.";
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

    LOG(VERBOSE) << "flushing stream "
                 << StringPrintf("0x%04x", mElementaryPID)
                 << " size = " << mBuffer->size();

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
    LOG(INFO) << "buffer has " << buffer->size() << " bytes left.";

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
    LOG(INFO) << "profile = " << profile;
    CHECK_NE(profile, 3u);
    unsigned sampling_freq_index = br.getBits(4);
    br.getBits(1);  // private_bit
    unsigned channel_configuration = br.getBits(3);
    CHECK_NE(channel_configuration, 0u);

    LOG(INFO) << "sampling_freq_index = " << sampling_freq_index;
    LOG(INFO) << "channel_configuration = " << channel_configuration;

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

    hexdump(csd->data(), csd->size());
    return csd;
}

void ATSParser::Stream::onPayloadData(
        unsigned PTS_DTS_flags, uint64_t PTS, uint64_t DTS,
        const uint8_t *data, size_t size) {
    LOG(VERBOSE) << "onPayloadData mStreamType="
                 << StringPrintf("0x%02x", mStreamType);

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

            LOG(INFO) << "sampleRate = " << sampleRate;
            LOG(INFO) << "channelCount = " << channelCount;

            meta->setInt32(kKeySampleRate, sampleRate);
            meta->setInt32(kKeyChannelCount, channelCount);

            meta->setData(kKeyESDS, 0, csd->data(), csd->size());
        }

        LOG(INFO) << "created source!";
        mSource = new AnotherPacketSource(meta);

        // fall through
    }

    CHECK(PTS_DTS_flags == 2 || PTS_DTS_flags == 3);
    buffer->meta()->setInt64("time", (PTS * 100) / 9);

    if (mStreamType == 0x0f) {
        // WHY???
        buffer->setRange(7, buffer->size() - 7);
    }

    mSource->queueAccessUnit(buffer);
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
    LOG(VERBOSE) << "  table_id = " << table_id;
    CHECK_EQ(table_id, 0x00u);

    unsigned section_syntax_indictor = br->getBits(1);
    LOG(VERBOSE) << "  section_syntax_indictor = " << section_syntax_indictor;
    CHECK_EQ(section_syntax_indictor, 1u);

    CHECK_EQ(br->getBits(1), 0u);
    LOG(VERBOSE) << "  reserved = " << br->getBits(2);

    unsigned section_length = br->getBits(12);
    LOG(VERBOSE) << "  section_length = " << section_length;
    CHECK((section_length & 0xc00) == 0);

    LOG(VERBOSE) << "  transport_stream_id = " << br->getBits(16);
    LOG(VERBOSE) << "  reserved = " << br->getBits(2);
    LOG(VERBOSE) << "  version_number = " << br->getBits(5);
    LOG(VERBOSE) << "  current_next_indicator = " << br->getBits(1);
    LOG(VERBOSE) << "  section_number = " << br->getBits(8);
    LOG(VERBOSE) << "  last_section_number = " << br->getBits(8);

    size_t numProgramBytes = (section_length - 5 /* header */ - 4 /* crc */);
    CHECK_EQ((numProgramBytes % 4), 0u);

    for (size_t i = 0; i < numProgramBytes / 4; ++i) {
        unsigned program_number = br->getBits(16);
        LOG(VERBOSE) << "    program_number = " << program_number;

        LOG(VERBOSE) << "    reserved = " << br->getBits(3);

        if (program_number == 0) {
            LOG(VERBOSE) << "    network_PID = "
                      << StringPrintf("0x%04x", br->getBits(13));
        } else {
            unsigned programMapPID = br->getBits(13);

            LOG(VERBOSE) << "    program_map_PID = "
                      << StringPrintf("0x%04x", programMapPID);

            mPrograms.push(new Program(programMapPID));
        }
    }

    LOG(VERBOSE) << "  CRC = " << StringPrintf("0x%08x", br->getBits(32));
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
        LOG(WARNING) << "PID " << StringPrintf("0x%04x", PID)
                     << " not handled.";
    }
}

void ATSParser::parseAdaptationField(ABitReader *br) {
    unsigned adaptation_field_length = br->getBits(8);
    if (adaptation_field_length > 0) {
        br->skipBits(adaptation_field_length * 8);  // XXX
    }
}

void ATSParser::parseTS(ABitReader *br) {
    LOG(VERBOSE) << "---";

    unsigned sync_byte = br->getBits(8);
    CHECK_EQ(sync_byte, 0x47u);

    LOG(VERBOSE) << "transport_error_indicator = " << br->getBits(1);

    unsigned payload_unit_start_indicator = br->getBits(1);
    LOG(VERBOSE) << "payload_unit_start_indicator = "
                 << payload_unit_start_indicator;

    LOG(VERBOSE) << "transport_priority = " << br->getBits(1);

    unsigned PID = br->getBits(13);
    LOG(VERBOSE) << "PID = " << StringPrintf("0x%04x", PID);

    LOG(VERBOSE) << "transport_scrambling_control = " << br->getBits(2);

    unsigned adaptation_field_control = br->getBits(2);
    LOG(VERBOSE) << "adaptation_field_control = " << adaptation_field_control;

    LOG(VERBOSE) << "continuity_counter = " << br->getBits(4);

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
