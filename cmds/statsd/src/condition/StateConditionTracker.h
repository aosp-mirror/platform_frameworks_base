/*
 * Copyright 2018, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#pragma once

#include <gtest/gtest_prod.h>
#include "ConditionTracker.h"
#include "config/ConfigKey.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "stats_util.h"

namespace android {
namespace os {
namespace statsd {

class StateConditionTracker : public virtual ConditionTracker {
public:
    StateConditionTracker(const ConfigKey& key, const int64_t& id, const int index,
                 const SimplePredicate& simplePredicate,
                 const std::unordered_map<int64_t, int>& trackerNameIndexMap,
                 const vector<Matcher> primaryKeys);

    ~StateConditionTracker();

    bool init(const std::vector<Predicate>& allConditionConfig,
              const std::vector<sp<ConditionTracker>>& allConditionTrackers,
              const std::unordered_map<int64_t, int>& conditionIdIndexMap,
              std::vector<bool>& stack) override;

    void evaluateCondition(const LogEvent& event,
                           const std::vector<MatchingState>& eventMatcherValues,
                           const std::vector<sp<ConditionTracker>>& mAllConditions,
                           std::vector<ConditionState>& conditionCache,
                           std::vector<bool>& changedCache) override;

    /**
     * Note: dimensionFields will be ignored in StateConditionTracker, because we demand metrics
     * must take the entire dimension fields from StateConditionTracker. This is to make implementation
     * simple and efficient.
     *
     * For example: wakelock duration by uid process states:
     *              dimension in condition must be {uid, process state}.
     */
    void isConditionMet(const ConditionKey& conditionParameters,
                        const std::vector<sp<ConditionTracker>>& allConditions,
                        const bool isPartialLink,
                        std::vector<ConditionState>& conditionCache) const override;

    virtual const std::set<HashableDimensionKey>* getChangedToTrueDimensions(
            const std::vector<sp<ConditionTracker>>& allConditions) const {
        return &mLastChangedToTrueDimensions;
    }

    virtual const std::set<HashableDimensionKey>* getChangedToFalseDimensions(
            const std::vector<sp<ConditionTracker>>& allConditions) const {
        return &mLastChangedToFalseDimensions;
    }

    bool IsChangedDimensionTrackable() const  override { return true; }

    bool IsSimpleCondition() const  override { return true; }

    bool equalOutputDimensions(
        const std::vector<sp<ConditionTracker>>& allConditions,
        const vector<Matcher>& dimensions) const override {
            return equalDimensions(mOutputDimensions, dimensions);
    }

    void getTrueSlicedDimensions(
            const std::vector<sp<ConditionTracker>>& allConditions,
            std::set<HashableDimensionKey>* dimensions) const override {
        for (const auto& itr : mSlicedState) {
            dimensions->insert(itr.second);
        }
    }

private:
    const ConfigKey mConfigKey;

    // The index of the LogEventMatcher which defines the start.
    int mStartLogMatcherIndex;

    std::set<HashableDimensionKey> mLastChangedToTrueDimensions;
    std::set<HashableDimensionKey> mLastChangedToFalseDimensions;

    std::vector<Matcher> mOutputDimensions;
    std::vector<Matcher> mPrimaryKeys;

    ConditionState mInitialValue;

    int mDimensionTag;

    void dumpState();

    bool hitGuardRail(const HashableDimensionKey& newKey);

    // maps from [primary_key] to [primary_key, exclusive_state].
    std::unordered_map<HashableDimensionKey, HashableDimensionKey> mSlicedState;

    FRIEND_TEST(StateConditionTrackerTest, TestStateChange);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
