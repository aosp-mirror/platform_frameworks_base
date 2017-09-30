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

#ifndef METRICS_MANAGER_H
#define METRICS_MANAGER_H

#include <cutils/log.h>
#include <log/logprint.h>
#include <unordered_map>
#include "../matchers/LogEntryMatcherManager.h"
#include "ConditionTracker.h"
#include "MetricProducer.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

namespace android {
namespace os {
namespace statsd {

// A MetricsManager is responsible for managing metrics from one single config source.
class MetricsManager {
public:
    MetricsManager(const StatsdConfig& config);

    ~MetricsManager();

    // Consume the stats log if it's interesting to this metric.
    void onLogEvent(const log_msg& logMsg);

    void finish();

private:
    const StatsdConfig mConfig;

    // All event tags that are interesting to my metrics.
    std::set<int> mTagIds;

    // The matchers that my metrics share.
    std::vector<LogEntryMatcher> mMatchers;

    // The conditions that my metrics share.
    std::vector<sp<ConditionTracker>> mConditionTracker;

    // the map from LogEntryMatcher names to the metrics that use this matcher.
    std::unordered_map<std::string, std::vector<std::unique_ptr<MetricProducer>>> mLogMatchers;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // METRICS_MANAGER_H
