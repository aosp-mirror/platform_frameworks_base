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

#ifndef A_TS_PARSER_H_

#define A_TS_PARSER_H_

#include <sys/types.h>

#include <media/stagefright/foundation/ABase.h>
#include <media/stagefright/foundation/AMessage.h>
#include <utils/Vector.h>
#include <utils/RefBase.h>

namespace android {

struct ABitReader;
struct ABuffer;
struct MediaSource;

struct ATSParser : public RefBase {
    enum DiscontinuityType {
        DISCONTINUITY_NONE,
        DISCONTINUITY_SEEK,
        DISCONTINUITY_FORMATCHANGE
    };

    enum Flags {
        // The 90kHz clock (PTS/DTS) is absolute, i.e. PTS=0 corresponds to
        // a media time of 0.
        // If this flag is _not_ specified, the first PTS encountered in a
        // program of this stream will be assumed to correspond to media time 0
        // instead.
        TS_TIMESTAMPS_ARE_ABSOLUTE = 1
    };

    ATSParser(uint32_t flags = 0);

    status_t feedTSPacket(const void *data, size_t size);

    void signalDiscontinuity(
            DiscontinuityType type, const sp<AMessage> &extra);

    void signalEOS(status_t finalResult);

    enum SourceType {
        VIDEO,
        AUDIO
    };
    sp<MediaSource> getSource(SourceType type);

    bool PTSTimeDeltaEstablished();

protected:
    virtual ~ATSParser();

private:
    enum {
        // From ISO/IEC 13818-1: 2000 (E), Table 2-29
        STREAMTYPE_MPEG1_VIDEO          = 0x01,
        STREAMTYPE_MPEG2_VIDEO          = 0x02,
        STREAMTYPE_MPEG1_AUDIO          = 0x03,
        STREAMTYPE_MPEG2_AUDIO          = 0x04,
        STREAMTYPE_MPEG2_AUDIO_ATDS     = 0x0f,
        STREAMTYPE_MPEG4_VIDEO          = 0x10,
        STREAMTYPE_H264                 = 0x1b,
    };

    struct Program;
    struct Stream;

    uint32_t mFlags;
    Vector<sp<Program> > mPrograms;

    void parseProgramAssociationTable(ABitReader *br);
    void parseProgramMap(ABitReader *br);
    void parsePES(ABitReader *br);

    status_t parsePID(
        ABitReader *br, unsigned PID,
        unsigned payload_unit_start_indicator);

    void parseAdaptationField(ABitReader *br);
    status_t parseTS(ABitReader *br);

    DISALLOW_EVIL_CONSTRUCTORS(ATSParser);
};

}  // namespace android

#endif  // A_TS_PARSER_H_
