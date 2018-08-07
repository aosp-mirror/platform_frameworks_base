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

    void isConditionMet(const ConditionKey& conditionParameters,
                        const std::vector<sp<ConditionTracker>>& allConditions,
                        const vector<Matcher>& dimensionFields,
                        const bool isSubOutputDimensionFields,
                        const bool isPartialLink,
                        std::vector<ConditionState>& conditionCache,
                        std::unordered_set<HashableDimensionKey>& dimensionsKeySet) const override;

    ConditionState getMetConditionDimension(
            const std::vector<sp<ConditionTracker>>& allConditions,
            const vector<Matcher>& dimensionFields,
            const bool isSubOutputDimensionFields,
            std::unordered_set<HashableDimensionKey>& dimensionsKeySet) const override;

    // Only one child predicate can have dimension.
    const std::set<HashableDimensionKey>* getChangedToTrueDimensions(
            const std::vector<sp<ConditionTracker>>& allConditions) const override {
        for (const auto& child : mChildren) {
            auto result = allConditions[child]->getChangedToTrueDimensions(allConditions);
            if (result != nullptr) {
                return result;
            }
        }
        return nullptr;
    }

    // Only one child predicate can have dimension.
    const std::set<HashableDimensionKey>* getChangedToFalseDimensions(
            const std::vector<sp<ConditionTracker>>& allConditions) const override {
        for (const auto& child : mChildren) {
            auto result = allConditions[child]->getChangedToFalseDimensions(allConditions);
            if (result != nullptr) {
                return result;
            }
        }
        return nullptr;
    }

    bool IsSimpleCondition() const  override { return false; }

    bool IsChangedDimensionTrackable() const  override {
        return mLogicalOperation == LogicalOperation::AND && mSlicedChildren.size() == 1;
    }

    bool equalOutputDimensions(
        const std::vector<sp<ConditionTracker>>& allConditions,
        const vector<Matcher>& dimensions) const override;

    void getTrueSlicedDimensions(
            const std::vector<sp<ConditionTracker>>& allConditions,
            std::set<HashableDimensionKey>* dimensions) const override {
        if (mSlicedChildren.size() == 1) {
            return allConditions[mSlicedChildren.front()]->getTrueSlicedDimensions(
                allConditions, dimensions);
        }
    }


private:
    LogicalOperation mLogicalOperation;

    // Store index of the children Predicates.
    // We don't store string name of the Children, because we want to get rid of the hash map to
    // map the name to object. We don't want to store smart pointers to children, because it
    // increases the risk of circular dependency and memory leak.
    std::vector<int> mChildren;

    std::vector<int> mSlicedChildren;
    std::vector<int> mUnSlicedChildren;

};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // COMBINATION_CONDITION_TRACKER_H
