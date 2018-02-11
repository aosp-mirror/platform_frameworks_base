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
#include "KernelUidCpuClusterTimeReader.h"
#include "guardrail/StatsdStats.h"
#include "logd/LogEvent.h"
#include "statslog.h"

using std::make_shared;
using std::shared_ptr;
using std::ifstream;

namespace android {
namespace os {
namespace statsd {

static const string sProcFile = "/proc/uid_concurrent_policy_time";
static const int kLineBufferSize = 1024;

/**
 * Reads /proc/uid_concurrent_policy_time which has the format:
 * policy0: X policy4: Y (there are X cores on policy0, Y cores on policy4)
 * [uid0]: [time-0-0] [time-0-1] ... [time-1-0] [time-1-1] ...
 * [uid1]: [time-0-0] [time-0-1] ... [time-1-0] [time-1-1] ...
 * ...
 * Time-X-Y means the time a UID spent on clusterX running concurrently with Y other processes.
 * The file contains a monotonically increasing count of time for a single boot.
 */
KernelUidCpuClusterTimeReader::KernelUidCpuClusterTimeReader() : StatsPuller(android::util::CPU_CLUSTER_TIME) {
}

bool KernelUidCpuClusterTimeReader::PullInternal(vector<shared_ptr<LogEvent>>* data) {
    data->clear();

    ifstream fin;
    fin.open(sProcFile);
    if (!fin.good()) {
        VLOG("Failed to read pseudo file %s", sProcFile.c_str());
        return false;
    }

    uint64_t timestamp = time(nullptr) * NS_PER_SEC;
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
            auto ptr = make_shared<LogEvent>(mTagId, timestamp);
            ptr->write(uid);
            ptr->write(idx);
            ptr->write(timeMs);
            ptr->init();
            data->push_back(ptr);
            VLOG("uid %lld, freq idx %d, cluster time %lld", (long long)uid, idx, (long long)timeMs);
            idx++;
            pch = strtok(NULL, " ");
        } while (pch != NULL);
    }
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
