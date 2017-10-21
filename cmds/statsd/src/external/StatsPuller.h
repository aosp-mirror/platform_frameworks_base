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

#include <android/os/StatsLogEventWrapper.h>
#include <utils/String16.h>
#include <vector>

using android::os::StatsLogEventWrapper;
using std::vector;

namespace android {
namespace os {
namespace statsd {

class StatsPuller {
public:
    virtual ~StatsPuller(){};

    virtual vector<StatsLogEventWrapper> pull() = 0;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATSD_STATSPULLER_H
