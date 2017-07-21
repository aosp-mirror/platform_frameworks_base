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

#include "IncidentHelper.h"
#include "ih_util.h"

#include "frameworks/base/core/proto/android/os/kernelwake.pb.h"
#include "frameworks/base/core/proto/android/os/procrank.pb.h"

#include <android-base/file.h>
#include <unistd.h>
#include <string>
#include <vector>

using namespace android::base;
using namespace android::os;
using namespace std;

// ================================================================================
status_t ReverseParser::Parse(const int in, const int out) const
{
    string content;
    if (!ReadFdToString(in, &content)) {
        fprintf(stderr, "[%s]Failed to read data from incidentd\n", this->name.string());
        return -1;
    }
    // reverse the content
    reverse(content.begin(), content.end());
    if (!WriteStringToFd(content, out)) {
        fprintf(stderr, "[%s]Failed to write data to incidentd\n", this->name.string());
        return -1;
    }
    return NO_ERROR;
}

// ================================================================================
// This list must be in order and sync with kernelwake.proto
const char* kernel_wake_headers[] = {
    "name",                  // id:  1
    "active_count",          // id:  2
    "event_count",           // id:  3
    "wakeup_count",          // id:  4
    "expire_count",          // id:  5
    "active_since",          // id:  6
    "total_time",            // id:  7
    "max_time",              // id:  8
    "last_change",           // id:  9
    "prevent_suspend_time",  // id: 10
};

const string KERNEL_WAKEUP_LINE_DELIMITER = "\t";

status_t KernelWakesParser::Parse(const int in, const int out) const {
    Reader reader(in);
    string line;
    header_t header;  // the header of /d/wakeup_sources
    record_t record;  // retain each record
    int nline = 0;

    KernelWakeSources proto;

    // parse line by line
    while (reader.readLine(line)) {
        if (line.empty()) continue;
        // parse head line
        if (nline++ == 0) {
            split(line, header, KERNEL_WAKEUP_LINE_DELIMITER);
            if (!assertHeaders(kernel_wake_headers, header)) {
                fprintf(stderr, "[%s]Bad header:\n%s\n", this->name.string(), line.c_str());
                return BAD_VALUE;
            }
            continue;
        }

        // parse for each record, the line delimiter is \t only!
        split(line, record, KERNEL_WAKEUP_LINE_DELIMITER);

        if (record.size() != header.size()) {
            // TODO: log this to incident report!
            fprintf(stderr, "[%s]Line %d has missing fields\n%s\n", this->name.string(), nline, line.c_str());
            continue;
        }

        WakeupSourceProto* source = proto.add_wakeup_sources();
        source->set_name(record.at(0).c_str());
        // below are int32
        source->set_active_count(atoi(record.at(1).c_str()));
        source->set_event_count(atoi(record.at(2).c_str()));
        source->set_wakeup_count(atoi(record.at(3).c_str()));
        source->set_expire_count(atoi(record.at(4).c_str()));
        // below are int64
        source->set_active_since(atol(record.at(5).c_str()));
        source->set_total_time(atol(record.at(6).c_str()));
        source->set_max_time(atol(record.at(7).c_str()));
        source->set_last_change(atol(record.at(8).c_str()));
        source->set_prevent_suspend_time(atol(record.at(9).c_str()));
    }

    if (!reader.ok(line)) {
        fprintf(stderr, "Bad read from fd %d: %s\n", in, line.c_str());
        return -1;
    }

    if (!proto.SerializeToFileDescriptor(out)) {
        fprintf(stderr, "[%s]Error writing proto back\n", this->name.string());
        return -1;
    }
    fprintf(stderr, "[%s]Proto size: %d bytes\n", this->name.string(), proto.ByteSize());
    return NO_ERROR;
}

// ================================================================================
const char* procrank_headers[] = {
    "PID",          // id:  1
    "Vss",          // id:  2
    "Rss",          // id:  3
    "Pss",          // id:  4
    "Uss",          // id:  5
    "Swap",         // id:  6
    "PSwap",        // id:  7
    "USwap",        // id:  8
    "ZSwap",        // id:  9
    "cmdline",      // id: 10
};

status_t ProcrankParser::Parse(const int in, const int out) const {
    Reader reader(in);
    string line, content;
    header_t header;  // the header of /d/wakeup_sources
    record_t record;  // retain each record
    int nline = 0;

    Procrank proto;

    // parse line by line
    while (reader.readLine(line)) {
        if (line.empty()) continue;

        // parse head line
        if (nline++ == 0) {
            split(line, header);
            if (!assertHeaders(procrank_headers, header)) {
                fprintf(stderr, "[%s]Bad header:\n%s\n", this->name.string(), line.c_str());
                return BAD_VALUE;
            }
            continue;
        }

        split(line, record);
        if (record.size() != header.size()) {
            if (record[record.size() - 1] == "TOTAL") { // TOTAL record
                ProcessProto* total = proto.mutable_summary()->mutable_total();
                total->set_pss(atol(record.at(0).substr(0, record.at(0).size() - 1).c_str()));
                total->set_uss(atol(record.at(1).substr(0, record.at(1).size() - 1).c_str()));
                total->set_swap(atol(record.at(2).substr(0, record.at(2).size() - 1).c_str()));
                total->set_pswap(atol(record.at(3).substr(0, record.at(3).size() - 1).c_str()));
                total->set_uswap(atol(record.at(4).substr(0, record.at(4).size() - 1).c_str()));
                total->set_zswap(atol(record.at(5).substr(0, record.at(5).size() - 1).c_str()));
            } else if (record[0] == "ZRAM:") {
                split(line, record, ":");
                proto.mutable_summary()->mutable_zram()->set_raw_text(record[1]);
            } else if (record[0] == "RAM:") {
                split(line, record, ":");
                proto.mutable_summary()->mutable_ram()->set_raw_text(record[1]);
            } else {
                fprintf(stderr, "[%s]Line %d has missing fields\n%s\n", this->name.string(), nline,
                    line.c_str());
            }
            continue;
        }

        ProcessProto* process = proto.add_processes();
        // int32
        process->set_pid(atoi(record.at(0).c_str()));
        // int64, remove 'K' at the end
        process->set_vss(atol(record.at(1).substr(0, record.at(1).size() - 1).c_str()));
        process->set_rss(atol(record.at(2).substr(0, record.at(2).size() - 1).c_str()));
        process->set_pss(atol(record.at(3).substr(0, record.at(3).size() - 1).c_str()));
        process->set_uss(atol(record.at(4).substr(0, record.at(4).size() - 1).c_str()));
        process->set_swap(atol(record.at(5).substr(0, record.at(5).size() - 1).c_str()));
        process->set_pswap(atol(record.at(6).substr(0, record.at(6).size() - 1).c_str()));
        process->set_uswap(atol(record.at(7).substr(0, record.at(7).size() - 1).c_str()));
        process->set_zswap(atol(record.at(8).substr(0, record.at(8).size() - 1).c_str()));
        // string
        process->set_cmdline(record.at(9));
    }

    if (!reader.ok(line)) {
        fprintf(stderr, "Bad read from fd %d: %s\n", in, line.c_str());
        return -1;
    }

    if (!proto.SerializeToFileDescriptor(out)) {
        fprintf(stderr, "[%s]Error writing proto back\n", this->name.string());
        return -1;
    }
    fprintf(stderr, "[%s]Proto size: %d bytes\n", this->name.string(), proto.ByteSize());
    return NO_ERROR;
}