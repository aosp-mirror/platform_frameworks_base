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

#ifndef SIMPLE_CONDITION_TRACKER_H
#define SIMPLE_CONDITION_TRACKER_H

#include <gtest/gtest_prod.h>
#include "ConditionTracker.h"
#include "config/ConfigKey.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "stats_util.h"

namespace android {
namespace os {
namespace statsd {

class SimpleConditionTracker : public virtual ConditionTracker {
public:
    SimpleConditionTracker(const ConfigKey& key, const std::string& name, const int index,
                           const SimpleCondition& simpleCondition,
                           const std::unordered_map<std::string, int>& trackerNameIndexMap);

    ~SimpleConditionTracker();

    bool init(const std::vector<Condition>& allConditionConfig,
              const std::vector<sp<ConditionTracker>>& allConditionTrackers,
              const std::unordered_map<std::string, int>& conditionNameIndexMap,
              std::vector<bool>& stack) override;

    void evaluateCondition(const LogEvent& event,
                           const std::vector<MatchingState>& eventMatcherValues,
                           const std::vector<sp<ConditionTracker>>& mAllConditions,
                           std::vector<ConditionState>& conditionCache,
                           std::vector<bool>& changedCache) override;

    void isConditionMet(const std::map<std::string, HashableDimensionKey>& conditionParameters,
                        const std::vector<sp<ConditionTracker>>& allConditions,
                        std::vector<ConditionState>& conditionCache) const override;

private:
    const ConfigKey mConfigKey;
    // The index of the LogEventMatcher which defines the start.
    int mStartLogMatcherIndex;

    // The index of the LogEventMatcher which defines the end.
    int mStopLogMatcherIndex;

    // if the start end needs to be nested.
    bool mCountNesting;

    // The index of the LogEventMatcher which defines the stop all.
    int mStopAllLogMatcherIndex;

    ConditionState mInitialValue;

    std::vector<KeyMatcher> mOutputDimension;

    std::map<HashableDimensionKey, int> mSlicedConditionState;

    void handleStopAll(std::vector<ConditionState>& conditionCache,
                       std::vector<bool>& changedCache);

    void handleConditionEvent(const HashableDimensionKey& outputKey, bool matchStart,
                              std::vector<ConditionState>& conditionCache,
                              std::vector<bool>& changedCache);

    bool hitGuardRail(const HashableDimensionKey& newKey);

    FRIEND_TEST(SimpleConditionTrackerTest, TestSlicedCondition);
    FRIEND_TEST(SimpleConditionTrackerTest, TestSlicedWithNoOutputDim);
    FRIEND_TEST(SimpleConditionTrackerTest, TestStopAll);
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // SIMPLE_CONDITION_TRACKER_H
