/*
 * Copyright (C) 2018 The Android Open Source Project
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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include <fstream>

#include "KernelUidCpuActiveTimeReader.h"
#include "guardrail/StatsdStats.h"
#include "logd/LogEvent.h"
#include "statslog.h"
#include "stats_log_util.h"

using std::make_shared;
using std::shared_ptr;
using std::ifstream;

namespace android {
namespace os {
namespace statsd {

static const string sProcFile = "/proc/uid_concurrent_active_time";
static const int kLineBufferSize = 1024;

/**
 * Reads /proc/uid_concurrent_active_time which has the format:
 * active: X (X is # cores)
 * [uid0]: [time-0] [time-1] [time-2] ... (# entries = # cores)
 * [uid1]: [time-0] [time-1] [time-2] ... ...
 * ...
 * Time-N means the CPU time a UID spent running concurrently with N other processes.
 * The file contains a monotonically increasing count of time for a single boot.
 */
KernelUidCpuActiveTimeReader::KernelUidCpuActiveTimeReader() : StatsPuller(android::util::CPU_ACTIVE_TIME) {
}

bool KernelUidCpuActiveTimeReader::PullInternal(vector<shared_ptr<LogEvent>>* data) {
    data->clear();

    ifstream fin;
    fin.open(sProcFile);
    if (!fin.good()) {
        VLOG("Failed to read pseudo file %s", sProcFile.c_str());
        return false;
    }

    int64_t wallClockTimestampNs = getWallClockNs();
    int64_t elapsedTimestampNs = getElapsedRealtimeNs();

    char buf[kLineBufferSize];
    char* pch;
    while (!fin.eof()) {
        fin.getline(buf, kLineBufferSize);
        pch = strtok(buf, " :");
        if (pch == NULL) break;
        uint64_t uid = std::stoull(pch);
        pch = strtok(NULL, " ");
        uint64_t timeMs;
        int idx = 0;
        do {
            timeMs = std::stoull(pch);
            auto ptr = make_shared<LogEvent>(mTagId, wallClockTimestampNs, elapsedTimestampNs);
            ptr->write(uid);
            ptr->write(idx);
            ptr->write(timeMs);
            ptr->init();
            data->push_back(ptr);
            VLOG("uid %lld, freq idx %d, active time %lld", (long long)uid, idx, (long long)timeMs);
            idx++;
            pch = strtok(NULL, " ");
        } while (pch != NULL);
    }
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
