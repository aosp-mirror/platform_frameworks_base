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
#ifndef COMBINATION_ATOM_MATCHING_TRACKER_H
#define COMBINATION_ATOM_MATCHING_TRACKER_H

#include <unordered_map>
#include <vector>

#include "AtomMatchingTracker.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

namespace android {
namespace os {
namespace statsd {

// Represents a AtomMatcher_Combination in the StatsdConfig.
class CombinationAtomMatchingTracker : public AtomMatchingTracker {
public:
    CombinationAtomMatchingTracker(const int64_t& id, const int index, const uint64_t protoHash);

    bool init(const std::vector<AtomMatcher>& allAtomMatchers,
              const std::vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
              const std::unordered_map<int64_t, int>& matcherMap, std::vector<bool>& stack);

    bool onConfigUpdated(const AtomMatcher& matcher, const int index,
                         const std::unordered_map<int64_t, int>& atomMatchingTrackerMap) override;

    ~CombinationAtomMatchingTracker();

    void onLogEvent(const LogEvent& event,
                    const std::vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
                    std::vector<MatchingState>& matcherResults) override;

private:
    LogicalOperation mLogicalOperation;

    std::vector<int> mChildren;

    FRIEND_TEST(ConfigUpdateTest, TestUpdateMatchers);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // COMBINATION_ATOM_MATCHING_TRACKER_H
