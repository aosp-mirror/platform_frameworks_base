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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include <fstream>
#include "external/CpuTimePerUidPuller.h"

#include "CpuTimePerUidPuller.h"
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

static const string sProcFile = "/proc/uid_cputime/show_uid_stat";
static const int kLineBufferSize = 1024;

/**
 * Reads /proc/uid_cputime/show_uid_stat which has the line format:
 *
 * uid: user_time_micro_seconds system_time_micro_seconds power_in_milli-amp-micro_seconds
 *
 * This provides the time a UID's processes spent executing in user-space and kernel-space.
 * The file contains a monotonically increasing count of time for a single boot.
 */
CpuTimePerUidPuller::CpuTimePerUidPuller() : StatsPuller(android::util::CPU_TIME_PER_UID) {
}

bool CpuTimePerUidPuller::PullInternal(vector<shared_ptr<LogEvent>>* data) {
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
        pch = strtok(buf, " ");
        uint64_t userTimeMs = std::stoull(pch);
        pch = strtok(buf, " ");
        uint64_t sysTimeMs = std::stoull(pch);

        auto ptr = make_shared<LogEvent>(android::util::CPU_TIME_PER_UID,
            wallClockTimestampNs, elapsedTimestampNs);
        ptr->write(uid);
        ptr->write(userTimeMs);
        ptr->write(sysTimeMs);
        ptr->init();
        data->push_back(ptr);
        VLOG("uid %lld, user time %lld, sys time %lld", (long long)uid, (long long)userTimeMs,
             (long long)sysTimeMs);
    }
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
