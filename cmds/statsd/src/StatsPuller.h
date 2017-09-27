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

#ifndef STATSD_STATSPULLER_H
#define STATSD_STATSPULLER_H

#include <utils/String16.h>

namespace android {
namespace os {
namespace statsd {

class StatsPuller {
public:
    // Enums of pulled data types (pullCodes)
    // These values must be kept in sync with com/android/server/stats/StatsCompanionService.java.
    // TODO: pull the constant from stats_events.proto instead
    const static int PULL_CODE_KERNEL_WAKELOCKS = 20;

    StatsPuller();
    ~StatsPuller();

    static String16 pull(int pullCode);
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif //STATSD_STATSPULLER_H
