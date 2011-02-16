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
#include "ESQueue.h"
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
    Program(ATSParser *parser, unsigned programMapPID);

    bool parsePID(
            unsigned pid, unsigned payload_unit_start_indicator,
            ABitReader *br);

    void signalDiscontinuity(DiscontinuityType type);
    void signalEOS(status_t finalResult);

    sp<MediaSource> getSource(SourceType type);

    int64_t convertPTSToTimestamp(uint64_t PTS);

    bool PTSTimeDeltaEstablished() const {
        return mFirstPTSValid;
    }

private:
    ATSParser *mParser;
    unsigned mProgramMapPID;
    KeyedVector<unsigned, sp<Stream> > mStreams;
    bool mFirstPTSValid;
    uint64_t mFirstPTS;

    void parseProgramMap(ABitReader *br);

    DISALLOW_EVIL_CONSTRUCTORS(Program);
};

struct ATSParser::Stream : public RefBase {
    Stream(Program *program, unsigned elementaryPID, unsigned streamType);

    unsigned type() const { return mStreamType; }
    unsigned pid() const { return mElementaryPID; }
    void setPID(unsigned pid) { mElementaryPID = pid; }

    void parse(
            unsigned payload_unit_start_indicator,
            ABitReader *br);

    void signalDiscontinuity(DiscontinuityType type);
    void signalEOS(status_t finalResult);

    sp<MediaSource> getSource(SourceType type);

protected:
    virtual ~Stream();

private:
    Program *mProgram;
    unsigned mElementaryPID;
    unsigned mStreamType;

    sp<ABuffer> mBuffer;
    sp<AnotherPacketSource> mSource;
    bool mPayloadStarted;
    DiscontinuityType mPendingDiscontinuity;

    ElementaryStreamQueue mQueue;

    void flush();
    void parsePES(ABitReader *br);

    void onPayloadData(
            unsigned PTS_DTS_flags, uint64_t PTS, uint64_t DTS,
            const uint8_t *data, size_t size);

    void extractAACFrames(const sp<ABuffer> &buffer);

    void deferDiscontinuity(DiscontinuityType type);

    DISALLOW_EVIL_CONSTRUCTORS(Stream);
};

////////////////////////////////////////////////////////////////////////////////

ATSParser::Program::Program(ATSParser *parser, unsigned programMapPID)
    : mParser(parser),
      mProgramMapPID(programMapPID),
      mFirstPTSValid(false),
      mFirstPTS(0) {
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

void ATSParser::Program::signalDiscontinuity(DiscontinuityType type) {
    for (size_t i = 0; i < mStreams.size(); ++i) {
        mStreams.editValueAt(i)->signalDiscontinuity(type);
    }
}

void ATSParser::Program::signalEOS(status_t finalResult) {
    for (size_t i = 0; i < mStreams.size(); ++i) {
        mStreams.editValueAt(i)->signalEOS(finalResult);
    }
}

struct StreamInfo {
    unsigned mType;
    unsigned mPID;
};

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
    CHECK_EQ(section_length & 0xc00, 0u);
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
    CHECK_EQ(program_info_length & 0xc00, 0u);

    br->skipBits(program_info_length * 8);  // skip descriptors

    Vector<StreamInfo> infos;

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
        CHECK_EQ(ES_info_length & 0xc00, 0u);

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

        StreamInfo info;
        info.mType = streamType;
        info.mPID = elementaryPID;
        infos.push(info);

        infoBytesRemaining -= 5 + ES_info_length;
    }

    CHECK_EQ(infoBytesRemaining, 0u);
    MY_LOGV("  CRC = 0x%08x", br->getBits(32));

    bool PIDsChanged = false;
    for (size_t i = 0; i < infos.size(); ++i) {
        StreamInfo &info = infos.editItemAt(i);

        ssize_t index = mStreams.indexOfKey(info.mPID);

        if (index >= 0 && mStreams.editValueAt(index)->type() != info.mType) {
            LOGI("uh oh. stream PIDs have changed.");
            PIDsChanged = true;
            break;
        }
    }

    if (PIDsChanged) {
        mStreams.clear();
    }

    for (size_t i = 0; i < infos.size(); ++i) {
        StreamInfo &info = infos.editItemAt(i);

        ssize_t index = mStreams.indexOfKey(info.mPID);

        if (index < 0) {
            sp<Stream> stream = new Stream(this, info.mPID, info.mType);
            mStreams.add(info.mPID, stream);

            if (PIDsChanged) {
                stream->signalDiscontinuity(DISCONTINUITY_FORMATCHANGE);
            }
        }
    }
}

sp<MediaSource> ATSParser::Program::getSource(SourceType type) {
    size_t index = (type == MPEG2ADTS_AUDIO) ? 0 : 0;

    for (size_t i = 0; i < mStreams.size(); ++i) {
        sp<MediaSource> source = mStreams.editValueAt(i)->getSource(type);
        if (source != NULL) {
            if (index == 0) {
                return source;
            }
            --index;
        }
    }

    return NULL;
}

int64_t ATSParser::Program::convertPTSToTimestamp(uint64_t PTS) {
    if (!mFirstPTSValid) {
        mFirstPTSValid = true;
        mFirstPTS = PTS;
        PTS = 0;
    } else if (PTS < mFirstPTS) {
        PTS = 0;
    } else {
        PTS -= mFirstPTS;
    }

    return (PTS * 100) / 9;
}

////////////////////////////////////////////////////////////////////////////////

ATSParser::Stream::Stream(
        Program *program, unsigned elementaryPID, unsigned streamType)
    : mProgram(program),
      mElementaryPID(elementaryPID),
      mStreamType(streamType),
      mBuffer(new ABuffer(192 * 1024)),
      mPayloadStarted(false),
      mPendingDiscontinuity(DISCONTINUITY_NONE),
      mQueue(streamType == 0x1b
              ? ElementaryStreamQueue::H264 : ElementaryStreamQueue::AAC) {
    mBuffer->setRange(0, 0);

    LOGV("new stream PID 0x%02x, type 0x%02x", elementaryPID, streamType);
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

void ATSParser::Stream::signalDiscontinuity(DiscontinuityType type) {
    mPayloadStarted = false;
    mBuffer->setRange(0, 0);

    switch (type) {
        case DISCONTINUITY_SEEK:
        case DISCONTINUITY_FORMATCHANGE:
        {
            bool isASeek = (type == DISCONTINUITY_SEEK);

            mQueue.clear(!isASeek);

            if (mSource != NULL) {
                mSource->queueDiscontinuity(type);
            } else {
                deferDiscontinuity(type);
            }
            break;
        }

        default:
            TRESPASS();
            break;
    }
}

void ATSParser::Stream::deferDiscontinuity(DiscontinuityType type) {
    if (type > mPendingDiscontinuity) {
        // Only upgrade discontinuities.
        mPendingDiscontinuity = type;
    }
}

void ATSParser::Stream::signalEOS(status_t finalResult) {
    if (mSource != NULL) {
        mSource->signalEOS(finalResult);
    }
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

        if (PES_packet_length != 0) {
            CHECK_GE(PES_packet_length, PES_header_data_length + 3);

            unsigned dataLength =
                PES_packet_length - 3 - PES_header_data_length;

            CHECK_GE(br->numBitsLeft(), dataLength * 8);

            onPayloadData(
                    PTS_DTS_flags, PTS, DTS, br->data(), dataLength);

            br->skipBits(dataLength * 8);
        } else {
            onPayloadData(
                    PTS_DTS_flags, PTS, DTS,
                    br->data(), br->numBitsLeft() / 8);

            size_t payloadSizeBits = br->numBitsLeft();
            CHECK_EQ(payloadSizeBits % 8, 0u);

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

void ATSParser::Stream::onPayloadData(
        unsigned PTS_DTS_flags, uint64_t PTS, uint64_t DTS,
        const uint8_t *data, size_t size) {
    LOGV("onPayloadData mStreamType=0x%02x", mStreamType);

    CHECK(PTS_DTS_flags == 2 || PTS_DTS_flags == 3);
    int64_t timeUs = mProgram->convertPTSToTimestamp(PTS);

    status_t err = mQueue.appendData(data, size, timeUs);

    if (err != OK) {
        return;
    }

    sp<ABuffer> accessUnit;
    while ((accessUnit = mQueue.dequeueAccessUnit()) != NULL) {
        if (mSource == NULL) {
            sp<MetaData> meta = mQueue.getFormat();

            if (meta != NULL) {
                LOGV("created source!");
                mSource = new AnotherPacketSource(meta);

                if (mPendingDiscontinuity != DISCONTINUITY_NONE) {
                    mSource->queueDiscontinuity(mPendingDiscontinuity);
                    mPendingDiscontinuity = DISCONTINUITY_NONE;
                }

                mSource->queueAccessUnit(accessUnit);
            }
        } else if (mQueue.getFormat() != NULL) {
            // After a discontinuity we invalidate the queue's format
            // and won't enqueue any access units to the source until
            // the queue has reestablished the new format.

            if (mSource->getFormat() == NULL) {
                mSource->setFormat(mQueue.getFormat());
            }
            mSource->queueAccessUnit(accessUnit);
        }
    }
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

void ATSParser::signalDiscontinuity(DiscontinuityType type) {
    for (size_t i = 0; i < mPrograms.size(); ++i) {
        mPrograms.editItemAt(i)->signalDiscontinuity(type);
    }
}

void ATSParser::signalEOS(status_t finalResult) {
    CHECK_NE(finalResult, (status_t)OK);

    for (size_t i = 0; i < mPrograms.size(); ++i) {
        mPrograms.editItemAt(i)->signalEOS(finalResult);
    }
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
    CHECK_EQ(section_length & 0xc00, 0u);

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

            mPrograms.push(new Program(this, programMapPID));
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

    unsigned continuity_counter = br->getBits(4);
    LOGV("continuity_counter = %u", continuity_counter);

    // LOGI("PID = 0x%04x, continuity_counter = %u", PID, continuity_counter);

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

bool ATSParser::PTSTimeDeltaEstablished() {
    if (mPrograms.isEmpty()) {
        return false;
    }

    return mPrograms.editItemAt(0)->PTSTimeDeltaEstablished();
}

}  // namespace android
