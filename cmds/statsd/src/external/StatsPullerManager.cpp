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

#define DEBUG true
#include "Log.h"

#include <android/os/IStatsCompanionService.h>
#include "KernelWakelockPuller.h"
#include "StatsService.h"
#include "external/StatsPullerManager.h"
#include "logd/LogEvent.h"
#include <cutils/log.h>
#include <algorithm>

#include <iostream>

using namespace android;

namespace android {
namespace os {
namespace statsd {

const int StatsPullerManager::KERNEL_WAKELOCKS = 1;

StatsPullerManager::StatsPullerManager() {
    mStatsPullers.insert(
            {static_cast<int>(KERNEL_WAKELOCKS), std::make_unique<KernelWakelockPuller>()});
}

vector<std::shared_ptr<LogEvent>> StatsPullerManager::Pull(int pullCode) {
    if (DEBUG) ALOGD("Initiating pulling %d", pullCode);

    vector<std::shared_ptr<LogEvent>> ret;
    if (mStatsPullers.find(pullCode) != mStatsPullers.end()) {
        vector<StatsLogEventWrapper> outputs = (mStatsPullers.find(pullCode)->second)->pull();
        for (const StatsLogEventWrapper& it : outputs) {
            log_msg tmp;
            tmp.entry_v1.len = it.bytes.size();
            // Manually set the header size to 28 bytes to match the pushed log events.
            tmp.entry.hdr_size = 28;
            // And set the received bytes starting after the 28 bytes reserved for header.
            std::copy(it.bytes.begin(), it.bytes.end(), tmp.buf + 28);
            std::shared_ptr<LogEvent> evt = std::make_shared<LogEvent>(tmp);
            ret.push_back(evt);
            // ret.emplace_back(tmp);
        }
        return ret;
    } else {
        ALOGD("Unknown pull code %d", pullCode);
        return ret;  // Return early since we don't know what to pull.
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
