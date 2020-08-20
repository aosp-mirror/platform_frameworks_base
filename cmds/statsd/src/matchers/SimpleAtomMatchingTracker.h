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

#ifndef SIMPLE_ATOM_MATCHING_TRACKER_H
#define SIMPLE_ATOM_MATCHING_TRACKER_H

#include <unordered_map>
#include <vector>

#include "AtomMatchingTracker.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "packages/UidMap.h"

namespace android {
namespace os {
namespace statsd {

class SimpleAtomMatchingTracker : public AtomMatchingTracker {
public:
    SimpleAtomMatchingTracker(const int64_t& id, const int index, const uint64_t protoHash,
                              const SimpleAtomMatcher& matcher, const sp<UidMap>& uidMap);

    ~SimpleAtomMatchingTracker();

    bool init(const std::vector<AtomMatcher>& allAtomMatchers,
              const std::vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
              const std::unordered_map<int64_t, int>& matcherMap,
              std::vector<bool>& stack) override;

    bool onConfigUpdated(const AtomMatcher& matcher, const int index,
                         const std::unordered_map<int64_t, int>& atomMatchingTrackerMap) override;

    void onLogEvent(const LogEvent& event,
                    const std::vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
                    std::vector<MatchingState>& matcherResults) override;

private:
    const SimpleAtomMatcher mMatcher;
    const sp<UidMap> mUidMap;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // SIMPLE_ATOM_MATCHING_TRACKER_H
