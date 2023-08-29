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
#include <unistd.h>

#include "frameworks/base/core/proto/android/os/cpufreq.proto.h"
#include "ih_util.h"
#include "CpuFreqParser.h"

using namespace android::os;

status_t
CpuFreqParser::Parse(const int in, const int out) const
{
    Reader reader(in);
    string line;

    // parse header
    reader.readLine(&line);
    header_t header = parseHeader(line, TAB_DELIMITER);
    if (header.size() < 1) {
        fprintf(stderr, "Bad header: %s\n", line.c_str());
        return BAD_VALUE;
    }
    const int numCpus = (int)header.size() - 1;
    vector<pair<int, long long>> cpucores[numCpus];

    // parse freq and time
    while (reader.readLine(&line)) {
        if (line.empty()) continue;

        record_t record = parseRecord(line, TAB_DELIMITER);
        if (record.size() != header.size()) {
            fprintf(stderr, "Bad line: %s\n", line.c_str());
            continue;
        }

        int freq = toInt(record[0]);
        for (int i=0; i<numCpus; i++) {
            if (strcmp(record[i+1].c_str(), "N/A") == 0) {
                continue;
            }
            cpucores[i].push_back(make_pair(freq, toLongLong(record[i+1])));
        }
    }

    ProtoOutputStream proto;

    long jiffyHz = sysconf(_SC_CLK_TCK);
    proto.write(CpuFreqProto::JIFFY_HZ, (int)jiffyHz);

    for (int i=0; i<numCpus; i++) {
        uint64_t token = proto.start(CpuFreqProto::CPU_FREQS);
        proto.write(CpuFreqProto::Stats::CPU_NAME, header[i+1]);
        for (vector<pair<int, long long>>::iterator it = cpucores[i].begin(); it != cpucores[i].end(); it++) {
            uint64_t stateToken = proto.start(CpuFreqProto::Stats::TIMES);
            proto.write(CpuFreqProto::Stats::TimeInState::STATE_KHZ, it->first);
            proto.write(CpuFreqProto::Stats::TimeInState::TIME_JIFFY, it->second);
            proto.end(stateToken);
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
