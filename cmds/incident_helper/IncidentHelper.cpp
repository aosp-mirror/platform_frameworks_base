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
static const string KERNEL_WAKEUP_LINE_DELIMITER = "\t";

static void SetWakeupSourceField(WakeupSourceProto* source, string name, string value) {
    if (name == "name") {
        source->set_name(value.c_str());
    } else if (name == "active_count") {
        source->set_active_count(atoi(value.c_str()));
    } else if (name == "event_count") {
        source->set_event_count(atoi(value.c_str()));
    } else if (name == "wakeup_count") {
        source->set_wakeup_count(atoi(value.c_str()));
    } else if (name == "expire_count") {
        source->set_expire_count(atoi(value.c_str()));
    } else if (name == "active_count") {
        source->set_active_since(atol(value.c_str()));
    } else if (name == "total_time") {
        source->set_total_time(atol(value.c_str()));
    } else if (name == "max_time") {
        source->set_max_time(atol(value.c_str()));
    } else if (name == "last_change") {
        source->set_last_change(atol(value.c_str()));
    } else if (name == "prevent_suspend_time") {
        source->set_prevent_suspend_time(atol(value.c_str()));
    }
    // add new fields
}

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
        for (int i=0; i<(int)record.size(); i++) {
            SetWakeupSourceField(source, header[i], record[i]);
        }
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
// Remove K for numeric fields
static void SetProcessField(ProcessProto* process, string name, string value) {
    ssize_t len = value.size();
    if (name == "PID") {
        process->set_pid(atoi(value.c_str()));
    } else if (name == "Vss") {
        process->set_vss(atol(value.substr(0, len - 1).c_str()));
    } else if (name == "Rss") {
        process->set_rss(atol(value.substr(0, len - 1).c_str()));
    } else if (name == "Pss") {
        process->set_pss(atol(value.substr(0, len - 1).c_str()));
    } else if (name == "Uss") {
        process->set_uss(atol(value.substr(0, len - 1).c_str()));
    } else if (name == "Swap") {
        process->set_swap(atol(value.substr(0, len - 1).c_str()));
    } else if (name == "PSwap") {
        process->set_pswap(atol(value.substr(0, len - 1).c_str()));
    } else if (name == "USwap") {
        process->set_uswap(atol(value.substr(0, len - 1).c_str()));
    } else if (name == "ZSwap") {
        process->set_zswap(atol(value.substr(0, len - 1).c_str()));
    } else if (name == "cmdline") {
        process->set_cmdline(value);
    }
}

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
            continue;
        }

        split(line, record);
        if (record.size() != header.size()) {
            if (record[record.size() - 1] == "TOTAL") { // TOTAL record
                ProcessProto* total = proto.mutable_summary()->mutable_total();
                for (int i=1; i<=(int)record.size(); i++) {
                    SetProcessField(total, header[header.size() - i], record[record.size() - i]);
                }
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
        for (int i=0; i<(int)record.size(); i++) {
            SetProcessField(process, header[i], record[i]);
        }
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