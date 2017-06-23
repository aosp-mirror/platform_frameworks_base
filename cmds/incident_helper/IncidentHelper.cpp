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
#include "strutil.h"

#include "frameworks/base/core/proto/android/os/kernelwake.pb.h"

#include <algorithm>
#include <android-base/file.h>
#include <unistd.h>
#include <sstream>
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
    // read the content, this is not memory-efficient though since it loads everything
    // However the data will be held in proto anyway, and incident_helper is less critical
    string content;
    if (!ReadFdToString(in, &content)) {
        fprintf(stderr, "[%s]Failed to read data from incidentd\n", this->name.string());
        return -1;
    }

    istringstream iss(content);
    string line;
    vector<string> header;  // the header of /d/wakeup_sources
    vector<string> record;  // retain each record
    int nline = 0;

    KernelWakeSources proto;

    // parse line by line
    while (getline(iss, line)) {
        // parse head line
        if (nline == 0) {
          split(line, &header);
          if (!assertHeaders(kernel_wake_headers, header)) {
            fprintf(stderr, "[%s]Bad header:\n%s\n", this->name.string(), line.c_str());
            return BAD_VALUE;
          }
          nline++;
          continue;
        }

        // parse for each record, the line delimiter is \t only!
        split(line, &record, KERNEL_WAKEUP_LINE_DELIMITER);

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

        nline++;
    }

    fprintf(stderr, "[%s]Proto size: %d bytes\n", this->name.string(), proto.ByteSize());

    if (!proto.SerializeToFileDescriptor(out)) {
        fprintf(stderr, "[%s]Error writing proto back\n", this->name.string());
        return -1;
    }
    close(out);

    return NO_ERROR;
}
