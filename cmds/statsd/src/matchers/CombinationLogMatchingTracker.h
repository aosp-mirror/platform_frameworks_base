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
#ifndef COMBINATION_LOG_MATCHING_TRACKER_H
#define COMBINATION_LOG_MATCHING_TRACKER_H

#include <log/log_read.h>
#include <log/logprint.h>
#include <set>
#include <unordered_map>
#include <vector>
#include "LogMatchingTracker.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

namespace android {
namespace os {
namespace statsd {

// Represents a AtomMatcher_Combination in the StatsdConfig.
class CombinationLogMatchingTracker : public virtual LogMatchingTracker {
public:
    CombinationLogMatchingTracker(const int64_t& id, const int index);

    bool init(const std::vector<AtomMatcher>& allLogMatchers,
              const std::vector<sp<LogMatchingTracker>>& allTrackers,
              const std::unordered_map<int64_t, int>& matcherMap,
              std::vector<bool>& stack);

    ~CombinationLogMatchingTracker();

    void onLogEvent(const LogEvent& event,
                    const std::vector<sp<LogMatchingTracker>>& allTrackers,
                    std::vector<MatchingState>& matcherResults) override;

private:
    LogicalOperation mLogicalOperation;

    std::vector<int> mChildren;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // COMBINATION_LOG_MATCHING_TRACKER_H
