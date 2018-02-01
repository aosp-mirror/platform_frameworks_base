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

#pragma once

#include <android/os/StatsLogEventWrapper.h>
#include <utils/String16.h>
#include <mutex>
#include <vector>

#include "logd/LogEvent.h"
#include "guardrail/StatsdStats.h"

using android::os::StatsLogEventWrapper;

namespace android {
namespace os {
namespace statsd {

class StatsPuller {
public:
    StatsPuller(const int tagId);

    virtual ~StatsPuller() {}

    bool Pull(std::vector<std::shared_ptr<LogEvent>>* data);

    void ClearCache();

protected:
    // The atom tag id this puller pulls
    const int mTagId;

private:
    mutable std::mutex mLock;
    // Minimum time before this puller does actual pull again.
    // If a pull request comes before cooldown, a cached version from purevious pull
    // will be returned.
    // The actual value should be determined by individual pullers.
    long mCoolDownSec;
    // For puller stats
    long mMinPullIntervalSec = LONG_MAX;

    virtual bool PullInternal(std::vector<std::shared_ptr<LogEvent>>* data) = 0;

    // Cache of data from last pull. If next request comes before cool down finishes,
    // cached data will be returned.
    std::vector<std::shared_ptr<LogEvent>> mCachedData;

    long mLastPullTimeSec;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
