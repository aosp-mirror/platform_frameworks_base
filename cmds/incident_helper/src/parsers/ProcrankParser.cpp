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

#include "frameworks/base/core/proto/android/os/procrank.proto.h"
#include "ih_util.h"
#include "ProcrankParser.h"

using namespace android::os;

status_t
ProcrankParser::Parse(const int in, const int out) const
{
    Reader reader(in);
    string line;
    header_t header;  // the header of /d/wakeup_sources
    record_t record;  // retain each record
    int nline = 0;

    ProtoOutputStream proto;
    Table table(ProcrankProto::Process::_FIELD_NAMES, ProcrankProto::Process::_FIELD_IDS, ProcrankProto::Process::_FIELD_COUNT);
    string zram, ram, total;

    // parse line by line
    while (reader.readLine(&line)) {
        if (line.empty()) continue;

        // parse head line
        if (nline++ == 0) {
            header = parseHeader(line);
            continue;
        }

        if (stripPrefix(&line, "ZRAM:")) {
            zram = line;
            continue;
        }
        if (stripPrefix(&line, "RAM:")) {
            ram = line;
            continue;
        }

        record = parseRecord(line);
        if (record.size() != header.size()) {
            if (record[record.size() - 1] == "TOTAL") { // TOTAL record
                total = line;
            } else {
                fprintf(stderr, "[%s]Line %d has missing fields\n%s\n", this->name.string(), nline,
                    line.c_str());
            }
            continue;
        }

        uint64_t token = proto.start(ProcrankProto::PROCESSES);
        for (int i=0; i<(int)record.size(); i++) {
            if (!table.insertField(&proto, header[i], record[i])) {
                fprintf(stderr, "[%s]Line %d has bad value %s of %s\n",
                        this->name.string(), nline, header[i].c_str(), record[i].c_str());
            }
        }
        proto.end(token);
    }

    // add summary
    uint64_t token = proto.start(ProcrankProto::SUMMARY);
    if (!total.empty()) {
        record = parseRecord(total);
        uint64_t token = proto.start(ProcrankProto::Summary::TOTAL);
        for (int i=(int)record.size(); i>0; i--) {
            table.insertField(&proto, header[header.size() - i].c_str(), record[record.size() - i].c_str());
        }
        proto.end(token);
    }
    if (!zram.empty()) {
        uint64_t token = proto.start(ProcrankProto::Summary::ZRAM);
        proto.write(ProcrankProto::Summary::Zram::RAW_TEXT, zram);
        proto.end(token);
    }
    if (!ram.empty()) {
        uint64_t token = proto.start(ProcrankProto::Summary::RAM);
        proto.write(ProcrankProto::Summary::Ram::RAW_TEXT, ram);
        proto.end(token);
    }
    proto.end(token);

    if (!reader.ok(&line)) {
        fprintf(stderr, "Bad read from fd %d: %s\n", in, line.c_str());
        return -1;
    }

    if (!proto.flush(out)) {
        fprintf(stderr, "[%s]Error writing proto back\n", this->name.string());
        return -1;
    }
    fprintf(stderr, "[%s]Proto size: %zu bytes\n", this->name.string(), proto.size());
    return NO_ERROR;
}
