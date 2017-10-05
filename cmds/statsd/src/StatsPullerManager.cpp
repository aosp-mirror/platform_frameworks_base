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

#define LOG_TAG "StatsPullerManager"
#define DEBUG true

#include "StatsPullerManager.h"
#include <android/os/IStatsCompanionService.h>
#include <cutils/log.h>
#include "StatsService.h"
#include "KernelWakelockPuller.h"


using namespace android;

namespace android {
namespace os {
namespace statsd {

const int StatsPullerManager::KERNEL_WAKELOCKS = 1;

StatsPullerManager::StatsPullerManager() {
    mStatsPullers.insert(
            {static_cast<int>(KERNEL_WAKELOCKS), std::make_unique<KernelWakelockPuller>()});
}

String16 StatsPullerManager::pull(int pullCode) {
    if (DEBUG) ALOGD("Initiating pulling %d", pullCode);
    if (mStatsPullers.find(pullCode) != mStatsPullers.end()) {
        return (mStatsPullers.find(pullCode)->second)->pull();
    } else {
        ALOGD("Unknown pull code %d", pullCode);
        return String16();
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
