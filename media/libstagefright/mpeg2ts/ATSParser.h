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
#include <utils/Vector.h>
#include <utils/RefBase.h>

namespace android {

struct ABitReader;
struct MediaSource;

struct ATSParser : public RefBase {
    ATSParser();

    void feedTSPacket(const void *data, size_t size);

    enum SourceType {
        AVC_VIDEO,
        MPEG2ADTS_AUDIO
    };
    sp<MediaSource> getSource(SourceType type);

protected:
    virtual ~ATSParser();

private:
    struct Program;
    struct Stream;

    Vector<sp<Program> > mPrograms;

    void parseProgramAssociationTable(ABitReader *br);
    void parseProgramMap(ABitReader *br);
    void parsePES(ABitReader *br);

    void parsePID(
        ABitReader *br, unsigned PID,
        unsigned payload_unit_start_indicator);

    void parseAdaptationField(ABitReader *br);
    void parseTS(ABitReader *br);

    DISALLOW_EVIL_CONSTRUCTORS(ATSParser);
};

}  // namespace android

#endif  // A_TS_PARSER_H_
