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

#ifndef COMBINATION_CONDITION_TRACKER_H
#define COMBINATION_CONDITION_TRACKER_H

#include "ConditionTracker.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

namespace android {
namespace os {
namespace statsd {

class CombinationConditionTracker : public virtual ConditionTracker {
public:
    CombinationConditionTracker(const int64_t& id, const int index);

    ~CombinationConditionTracker();

    bool init(const std::vector<Predicate>& allConditionConfig,
              const std::vector<sp<ConditionTracker>>& allConditionTrackers,
              const std::unordered_map<int64_t, int>& conditionIdIndexMap,
              std::vector<bool>& stack) override;

    void evaluateCondition(const LogEvent& event,
                           const std::vector<MatchingState>& eventMatcherValues,
                           const std::vector<sp<ConditionTracker>>& mAllConditions,
                           std::vector<ConditionState>& conditionCache,
                           std::vector<bool>& changedCache) override;

    void isConditionMet(
        const ConditionKey& conditionParameters,
        const std::vector<sp<ConditionTracker>>& allConditions,
        const FieldMatcher& dimensionFields,
        std::vector<ConditionState>& conditionCache,
        std::unordered_set<HashableDimensionKey> &dimensionsKeySet) const override;

    ConditionState getMetConditionDimension(
            const std::vector<sp<ConditionTracker>>& allConditions,
            const FieldMatcher& dimensionFields,
            std::unordered_set<HashableDimensionKey> &dimensionsKeySet) const override;
private:
    LogicalOperation mLogicalOperation;

    // Store index of the children Predicates.
    // We don't store string name of the Children, because we want to get rid of the hash map to
    // map the name to object. We don't want to store smart pointers to children, because it
    // increases the risk of circular dependency and memory leak.
    std::vector<int> mChildren;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // COMBINATION_CONDITION_TRACKER_H
