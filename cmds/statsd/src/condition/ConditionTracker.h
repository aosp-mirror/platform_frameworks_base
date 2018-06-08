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

#include "condition/condition_util.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "matchers/LogMatchingTracker.h"
#include "matchers/matcher_util.h"

#include <log/logprint.h>
#include <utils/RefBase.h>

#include <unordered_set>
#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

class ConditionTracker : public virtual RefBase {
public:
    ConditionTracker(const int64_t& id, const int index)
        : mConditionId(id),
          mIndex(index),
          mInitialized(false),
          mTrackerIndex(),
          mNonSlicedConditionState(ConditionState::kUnknown),
          mSliced(false){};

    virtual ~ConditionTracker(){};

    inline const int64_t& getId() { return mConditionId; }

    // Initialize this ConditionTracker. This initialization is done recursively (DFS). It can also
    // be done in the constructor, but we do it separately because (1) easy to return a bool to
    // indicate whether the initialization is successful. (2) makes unit test easier.
    // allConditionConfig: the list of all Predicate config from statsd_config.
    // allConditionTrackers: the list of all ConditionTrackers (this is needed because we may also
    //                       need to call init() on children conditions)
    // conditionIdIndexMap: the mapping from condition id to its index.
    // stack: a bit map to keep track which nodes have been visited on the stack in the recursion.
    virtual bool init(const std::vector<Predicate>& allConditionConfig,
                      const std::vector<sp<ConditionTracker>>& allConditionTrackers,
                      const std::unordered_map<int64_t, int>& conditionIdIndexMap,
                      std::vector<bool>& stack) = 0;

    // evaluate current condition given the new event.
    // event: the new log event
    // eventMatcherValues: the results of the LogMatcherTrackers. LogMatcherTrackers always process
    //                     event before ConditionTrackers, because ConditionTracker depends on
    //                     LogMatchingTrackers.
    // mAllConditions: the list of all ConditionTracker
    // conditionCache: the cached non-sliced condition of the ConditionTrackers for this new event.
    // conditionChanged: the bit map to record whether the condition has changed.
    //                   If the condition has dimension, then any sub condition changes will report
    //                   conditionChanged.
    virtual void evaluateCondition(const LogEvent& event,
                                   const std::vector<MatchingState>& eventMatcherValues,
                                   const std::vector<sp<ConditionTracker>>& mAllConditions,
                                   std::vector<ConditionState>& conditionCache,
                                   std::vector<bool>& conditionChanged) = 0;

    // Return the current condition state.
    virtual ConditionState isConditionMet() const {
        return mNonSlicedConditionState;
    };

    // Query the condition with parameters.
    // [conditionParameters]: a map from condition name to the HashableDimensionKey to query the
    //                       condition.
    // [allConditions]: all condition trackers. This is needed because the condition evaluation is
    //                  done recursively
    // [dimensionFields]: the needed dimension fields which should be all or subset of the condition
    //                    tracker output dimension.
    // [isSubOutputDimensionFields]: true if the needed dimension fields which is strictly subset of
    //                               the condition tracker output dimension.
    // [isPartialLink]: true if the link specified by 'conditionParameters' contains all the fields
    //                  in the condition tracker output dimension.
    // [conditionCache]: the cache holding the condition evaluation values.
    // [dimensionsKeySet]: the dimensions where the sliced condition is true. For combination
    //                    condition, it assumes that only one child predicate is sliced.
    virtual void isConditionMet(
            const ConditionKey& conditionParameters,
            const std::vector<sp<ConditionTracker>>& allConditions,
            const vector<Matcher>& dimensionFields,
            const bool isSubOutputDimensionFields,
            const bool isPartialLink,
            std::vector<ConditionState>& conditionCache,
            std::unordered_set<HashableDimensionKey>& dimensionsKeySet) const = 0;

    virtual ConditionState getMetConditionDimension(
            const std::vector<sp<ConditionTracker>>& allConditions,
            const vector<Matcher>& dimensionFields,
            const bool isSubOutputDimensionFields,
            std::unordered_set<HashableDimensionKey>& dimensionsKeySet) const = 0;

    // return the list of LogMatchingTracker index that this ConditionTracker uses.
    virtual const std::set<int>& getLogTrackerIndex() const {
        return mTrackerIndex;
    }

    virtual void setSliced(bool sliced) {
        mSliced = mSliced | sliced;
    }

    inline bool isSliced() const {
        return mSliced;
    }

    virtual const std::set<HashableDimensionKey>* getChangedToTrueDimensions(
            const std::vector<sp<ConditionTracker>>& allConditions) const = 0;
    virtual const std::set<HashableDimensionKey>* getChangedToFalseDimensions(
            const std::vector<sp<ConditionTracker>>& allConditions) const = 0;

    inline int64_t getConditionId() const {
        return mConditionId;
    }

    virtual void getTrueSlicedDimensions(
        const std::vector<sp<ConditionTracker>>& allConditions,
        std::set<HashableDimensionKey>* dimensions) const = 0;

    virtual bool IsChangedDimensionTrackable() const = 0;

    virtual bool IsSimpleCondition() const = 0;

    virtual bool equalOutputDimensions(
        const std::vector<sp<ConditionTracker>>& allConditions,
        const vector<Matcher>& dimensions) const = 0;

    inline ConditionState getUnSlicedPartConditionState() const  {
        return mUnSlicedPart;
    }

protected:
    const int64_t mConditionId;

    // the index of this condition in the manager's condition list.
    const int mIndex;

    // if it's properly initialized.
    bool mInitialized;

    // the list of LogMatchingTracker index that this ConditionTracker uses.
    std::set<int> mTrackerIndex;

    ConditionState mNonSlicedConditionState;

    bool mSliced;
    ConditionState mUnSlicedPart;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
