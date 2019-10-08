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

#include "frameworks/base/core/proto/android/os/cpuinfo.proto.h"
#include "ih_util.h"
#include "CpuInfoParser.h"

using namespace android::os;

static void writeSuffixLine(ProtoOutputStream* proto, uint64_t fieldId,
        const string& line, const string& delimiter,
        const int count, const char* names[], const uint64_t ids[])
{
    record_t record = parseRecord(line, delimiter);
    uint64_t token = proto->start(fieldId);
    for (int i=0; i<(int)record.size(); i++) {
        for (int j=0; j<count; j++) {
            if (stripSuffix(&record[i], names[j], true)) {
                proto->write(ids[j], toInt(record[i]));
                break;
            }
        }
    }
    proto->end(token);
}

status_t
CpuInfoParser::Parse(const int in, const int out) const
{
    Reader reader(in);
    string line;
    header_t header;
    vector<int> columnIndices; // task table can't be split by purely delimiter, needs column positions.
    record_t record;
    int nline = 0;
    int diff = 0;
    bool nextToSwap = false;
    bool nextToUsage = false;

    ProtoOutputStream proto;
    Table table(CpuInfoProto::Task::_FIELD_NAMES, CpuInfoProto::Task::_FIELD_IDS, CpuInfoProto::Task::_FIELD_COUNT);
    table.addEnumTypeMap("s", CpuInfoProto::Task::_ENUM_STATUS_NAMES,
            CpuInfoProto::Task::_ENUM_STATUS_VALUES, CpuInfoProto::Task::_ENUM_STATUS_COUNT);
    table.addEnumTypeMap("pcy", CpuInfoProto::Task::_ENUM_POLICY_NAMES,
            CpuInfoProto::Task::_ENUM_POLICY_VALUES, CpuInfoProto::Task::_ENUM_POLICY_COUNT);

    // parse line by line
    while (reader.readLine(&line)) {
        if (line.empty()) continue;

        nline++;
        // The format changes from time to time in toybox/toys/posix/ps.c
        // With -H, it prints Threads instead of Tasks (FLAG(H)?"Thread":"Task")
        if (stripPrefix(&line, "Threads:")) {
            writeSuffixLine(&proto, CpuInfoProto::TASK_STATS, line, COMMA_DELIMITER,
                CpuInfoProto::TaskStats::_FIELD_COUNT,
                CpuInfoProto::TaskStats::_FIELD_NAMES,
                CpuInfoProto::TaskStats::_FIELD_IDS);
            continue;
        }
        if (stripPrefix(&line, "Mem:")) {
            writeSuffixLine(&proto, CpuInfoProto::MEM, line, COMMA_DELIMITER,
                CpuInfoProto::MemStats::_FIELD_COUNT,
                CpuInfoProto::MemStats::_FIELD_NAMES,
                CpuInfoProto::MemStats::_FIELD_IDS);
            continue;
        }
        if (stripPrefix(&line, "Swap:")) {
            writeSuffixLine(&proto, CpuInfoProto::SWAP, line, COMMA_DELIMITER,
                CpuInfoProto::MemStats::_FIELD_COUNT,
                CpuInfoProto::MemStats::_FIELD_NAMES,
                CpuInfoProto::MemStats::_FIELD_IDS);
            nextToSwap = true;
            continue;
        }

        if (nextToSwap) {
            writeSuffixLine(&proto, CpuInfoProto::CPU_USAGE, line, DEFAULT_WHITESPACE,
                CpuInfoProto::CpuUsage::_FIELD_COUNT,
                CpuInfoProto::CpuUsage::_FIELD_NAMES,
                CpuInfoProto::CpuUsage::_FIELD_IDS);
            nextToUsage = true;
            nextToSwap = false;
            continue;
        }

        // Header of tasks must be next to usage line
        if (nextToUsage) {
            // How to parse Header of Tasks:
            // PID   TID USER         PR  NI[%CPU]S VIRT  RES PCY CMD             NAME
            // After parsing, header = { PID, TID, USER, PR, NI, CPU, S, VIRT, RES, PCY, CMD, NAME }
            // And columnIndices will contain end index of each word.
            header = parseHeader(line, "[ %]");
            nextToUsage = false;

            // NAME is not in the list since we need to modify the end of the CMD index.
            const char* headerNames[] = { "PID", "TID", "USER", "PR", "NI", "CPU", "S", "VIRT", "RES", "PCY", "CMD", nullptr };
            if (!getColumnIndices(columnIndices, headerNames, line)) {
                return -1;
            }
            // Need to remove the end index of CMD and use the start index of NAME because CMD values contain spaces.
            // for example: ... CMD             NAME
            //              ... Jit thread pool com.google.android.gms.feedback
            // If use end index of CMD, parsed result = { "Jit", "thread pool com.google.android.gms.feedback" }
            // If use start index of NAME, parsed result = { "Jit thread pool", "com.google.android.gms.feedback" }
            int endCMD = columnIndices.back();
            columnIndices.pop_back();
            columnIndices.push_back(line.find("NAME", endCMD) - 1);
            // Add NAME index to complete the column list.
            columnIndices.push_back(columnIndices.back() + 4);
            continue;
        }

        record = parseRecordByColumns(line, columnIndices);
        diff = record.size() - header.size();
        if (diff < 0) {
            fprintf(stderr, "[%s]Line %d has %d missing fields\n%s\n", this->name.string(), nline, -diff, line.c_str());
            printRecord(record);
            continue;
        } else if (diff > 0) {
            fprintf(stderr, "[%s]Line %d has %d extra fields\n%s\n", this->name.string(), nline, diff, line.c_str());
            printRecord(record);
            continue;
        }

        uint64_t token = proto.start(CpuInfoProto::TASKS);
        for (int i=0; i<(int)record.size(); i++) {
            if (!table.insertField(&proto, header[i], record[i])) {
                fprintf(stderr, "[%s]Line %d fails to insert field %s with value %s\n",
                        this->name.string(), nline, header[i].c_str(), record[i].c_str());
            }
        }
        proto.end(token);
    }

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
