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
    long long token = proto->start(fieldId);
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
    bool nextToSwap = false;
    bool nextToUsage = false;

    ProtoOutputStream proto;
    Table table(CpuInfo::Task::_FIELD_NAMES, CpuInfo::Task::_FIELD_IDS, CpuInfo::Task::_FIELD_COUNT);
    table.addEnumTypeMap("s", CpuInfo::Task::_ENUM_STATUS_NAMES,
            CpuInfo::Task::_ENUM_STATUS_VALUES, CpuInfo::Task::_ENUM_STATUS_COUNT);
    table.addEnumTypeMap("pcy", CpuInfo::Task::_ENUM_POLICY_NAMES,
            CpuInfo::Task::_ENUM_POLICY_VALUES, CpuInfo::Task::_ENUM_POLICY_COUNT);

    // parse line by line
    while (reader.readLine(&line)) {
        if (line.empty()) continue;

        nline++;

        if (stripPrefix(&line, "Tasks:")) {
            writeSuffixLine(&proto, CpuInfo::TASK_STATS, line, COMMA_DELIMITER,
                CpuInfo::TaskStats::_FIELD_COUNT,
                CpuInfo::TaskStats::_FIELD_NAMES,
                CpuInfo::TaskStats::_FIELD_IDS);
            continue;
        }
        if (stripPrefix(&line, "Mem:")) {
            writeSuffixLine(&proto, CpuInfo::MEM, line, COMMA_DELIMITER,
                CpuInfo::MemStats::_FIELD_COUNT,
                CpuInfo::MemStats::_FIELD_NAMES,
                CpuInfo::MemStats::_FIELD_IDS);
            continue;
        }
        if (stripPrefix(&line, "Swap:")) {
            writeSuffixLine(&proto, CpuInfo::SWAP, line, COMMA_DELIMITER,
                CpuInfo::MemStats::_FIELD_COUNT,
                CpuInfo::MemStats::_FIELD_NAMES,
                CpuInfo::MemStats::_FIELD_IDS);
            nextToSwap = true;
            continue;
        }

        if (nextToSwap) {
            writeSuffixLine(&proto, CpuInfo::CPU_USAGE, line, DEFAULT_WHITESPACE,
                CpuInfo::CpuUsage::_FIELD_COUNT,
                CpuInfo::CpuUsage::_FIELD_NAMES,
                CpuInfo::CpuUsage::_FIELD_IDS);
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

            // NAME is not in the list since the last split index is default to the end of line.
            const char* headerNames[11] = { "PID", "TID", "USER", "PR", "NI", "CPU", "S", "VIRT", "RES", "PCY", "CMD" };
            size_t lastIndex = 0;
            for (int i = 0; i < 11; i++) {
                string s = headerNames[i];
                lastIndex = line.find(s, lastIndex);
                if (lastIndex == string::npos) {
                    fprintf(stderr, "Bad Task Header: %s\n", line.c_str());
                    return -1;
                }
                lastIndex += s.length();
                columnIndices.push_back(lastIndex);
            }
            // Need to remove the end index of CMD and use the start index of NAME because CMD values contain spaces.
            // for example: ... CMD             NAME
            //              ... Jit thread pool com.google.android.gms.feedback
            // If use end index of CMD, parsed result = { "Jit", "thread pool com.google.android.gms.feedback" }
            // If use start index of NAME, parsed result = { "Jit thread pool", "com.google.android.gms.feedback" }
            int endCMD = columnIndices.back();
            columnIndices.pop_back();
            columnIndices.push_back(line.find("NAME", endCMD) - 1);
            continue;
        }

        record = parseRecordByColumns(line, columnIndices);
        if (record.size() != header.size()) {
            fprintf(stderr, "[%s]Line %d has missing fields:\n%s\n", this->name.string(), nline, line.c_str());
            continue;
        }

        long long token = proto.start(CpuInfo::TASKS);
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
