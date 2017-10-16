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

#ifndef STATSD_STATSPULLERMANAGER_H
#define STATSD_STATSPULLERMANAGER_H

#include <utils/String16.h>
#include <unordered_map>
#include "external/StatsPuller.h"

namespace android {
namespace os {
namespace statsd {

const static int KERNEL_WAKELOCKS = 1;

class StatsPullerManager {
public:
    // Enums of pulled data types (pullCodes)
    // These values must be kept in sync with com/android/server/stats/StatsCompanionService.java.
    // TODO: pull the constant from stats_events.proto instead
    const static int KERNEL_WAKELOCKS;
    StatsPullerManager();

    String16 pull(const int pullCode);

private:
    std::unordered_map<int, std::unique_ptr<StatsPuller>> mStatsPullers;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATSD_STATSPULLERMANAGER_H
