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
#include "external/CpuTimePerUidFreqPuller.h"

#include "../guardrail/StatsdStats.h"
#include "CpuTimePerUidFreqPuller.h"
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

static const string sProcFile = "/proc/uid_time_in_state";
static const int kLineBufferSize = 1024;

/**
 * Reads /proc/uid_time_in_state which has the format:
 *
 * uid: [freq1] [freq2] [freq3] ...
 * [uid1]: [time in freq1] [time in freq2] [time in freq3] ...
 * [uid2]: [time in freq1] [time in freq2] [time in freq3] ...
 * ...
 *
 * This provides the times a UID's processes spent executing at each different cpu frequency.
 * The file contains a monotonically increasing count of time for a single boot.
 */
CpuTimePerUidFreqPuller::CpuTimePerUidFreqPuller()
    : StatsPuller(android::util::CPU_TIME_PER_UID_FREQ) {
}

bool CpuTimePerUidFreqPuller::PullInternal(vector<shared_ptr<LogEvent>>* data) {
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
    // first line prints the format and frequencies
    fin.getline(buf, kLineBufferSize);
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
            auto ptr = make_shared<LogEvent>(android::util::CPU_TIME_PER_UID_FREQ,
                wallClockTimestampNs, elapsedTimestampNs);
            ptr->write(uid);
            ptr->write(idx);
            ptr->write(timeMs);
            ptr->init();
            data->push_back(ptr);
            VLOG("uid %lld, freq idx %d, sys time %lld", (long long)uid, idx, (long long)timeMs);
            idx++;
            pch = strtok(NULL, " ");
        } while (pch != NULL);
    }
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
