/*
 * Copyright (C) 2017 The Android Open Source Project
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
#define LOG_TAG "incident_helper"

#include <android/util/ProtoOutputStream.h>

#include "frameworks/base/core/proto/android/os/kernelwake.proto.h"
#include "ih_util.h"
#include "KernelWakesParser.h"

using namespace android::os;

status_t
KernelWakesParser::Parse(const int in, const int out) const
{
    Reader reader(in);
    string line;
    header_t header;  // the header of /d/wakeup_sources
    record_t record;  // retain each record
    int nline = 0;

    ProtoOutputStream proto;
    Table table(KernelWakeSourcesProto::WakeupSource::_FIELD_NAMES,
            KernelWakeSourcesProto::WakeupSource::_FIELD_IDS,
            KernelWakeSourcesProto::WakeupSource::_FIELD_COUNT);

    // parse line by line
    while (reader.readLine(&line)) {
        if (line.empty()) continue;
        // parse head line
        if (nline++ == 0) {
            header = parseHeader(line, TAB_DELIMITER);
            continue;
        }

        // parse for each record, the line delimiter is \t only!
        record = parseRecord(line, TAB_DELIMITER);

        if (record.size() < header.size()) {
            // TODO: log this to incident report!
            fprintf(stderr, "[%s]Line %d has missing fields\n%s\n", this->name.c_str(), nline, line.c_str());
            continue;
        } else if (record.size() > header.size()) {
            // TODO: log this to incident report!
            fprintf(stderr, "[%s]Line %d has extra fields\n%s\n", this->name.c_str(), nline, line.c_str());
            continue;
        }

        uint64_t token = proto.start(KernelWakeSourcesProto::WAKEUP_SOURCES);
        for (int i=0; i<(int)record.size(); i++) {
            if (!table.insertField(&proto, header[i], record[i])) {
                fprintf(stderr, "[%s]Line %d has bad value %s of %s\n",
                        this->name.c_str(), nline, header[i].c_str(), record[i].c_str());
            }
        }
        proto.end(token);
    }

    if (!reader.ok(&line)) {
        fprintf(stderr, "Bad read from fd %d: %s\n", in, line.c_str());
        return -1;
    }

    if (!proto.flush(out)) {
        fprintf(stderr, "[%s]Error writing proto back\n", this->name.c_str());
        return -1;
    }
    fprintf(stderr, "[%s]Proto size: %zu bytes\n", this->name.c_str(), proto.size());
    return NO_ERROR;
}
