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
#include "matchers/AtomMatchingTracker.h"
#include "matchers/matcher_util.h"

#include <utils/RefBase.h>

#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

class ConditionTracker : public virtual RefBase {
public:
    ConditionTracker(const int64_t& id, const int index, const uint64_t protoHash)
        : mConditionId(id),
          mIndex(index),
          mInitialized(false),
          mTrackerIndex(),
          mUnSlicedPartCondition(ConditionState::kUnknown),
          mSliced(false),
          mProtoHash(protoHash){};

    virtual ~ConditionTracker(){};

    // Initialize this ConditionTracker. This initialization is done recursively (DFS). It can also
    // be done in the constructor, but we do it separately because (1) easy to return a bool to
    // indicate whether the initialization is successful. (2) makes unit test easier.
    // This function can also be called on config updates, in which case it does nothing other than
    // fill the condition cache with the current condition.
    // allConditionConfig: the list of all Predicate config from statsd_config.
    // allConditionTrackers: the list of all ConditionTrackers (this is needed because we may also
    //                       need to call init() on child conditions)
    // conditionIdIndexMap: the mapping from condition id to its index.
    // stack: a bit map to keep track which nodes have been visited on the stack in the recursion.
    // conditionCache: tracks initial conditions of all ConditionTrackers. returns the
    //                        current condition if called on a config update.
    virtual bool init(const std::vector<Predicate>& allConditionConfig,
                      const std::vector<sp<ConditionTracker>>& allConditionTrackers,
                      const std::unordered_map<int64_t, int>& conditionIdIndexMap,
                      std::vector<bool>& stack, std::vector<ConditionState>& conditionCache) = 0;

    // Update appropriate state on config updates. Primarily, all indices need to be updated.
    // This predicate and all of its children are guaranteed to be preserved across the update.
    // This function is recursive and will call onConfigUpdated on child conditions. It does not
    // manage cycle detection since all preserved conditions should not have any cycles.
    //
    // allConditionProtos: the new predicates.
    // index: the new index of this tracker in allConditionProtos and allConditionTrackers.
    // allConditionTrackers: the list of all ConditionTrackers (this is needed because we may also
    //                       need to call onConfigUpdated() on child conditions)
    // atomMatchingTrackerMap: map of atom matcher id to index after the config update.
    // conditionTrackerMap: map of condition tracker id to index after the config update.
    // returns whether or not the update is successful.
    virtual bool onConfigUpdated(const std::vector<Predicate>& allConditionProtos, const int index,
                                 const std::vector<sp<ConditionTracker>>& allConditionTrackers,
                                 const std::unordered_map<int64_t, int>& atomMatchingTrackerMap,
                                 const std::unordered_map<int64_t, int>& conditionTrackerMap) {
        mIndex = index;
        return true;
    }

    // evaluate current condition given the new event.
    // event: the new log event
    // eventMatcherValues: the results of the AtomMatchingTrackers. AtomMatchingTrackers always
    //                     process event before ConditionTrackers, because ConditionTracker depends
    //                     on AtomMatchingTrackers.
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

    // Query the condition with parameters.
    // [conditionParameters]: a map from condition name to the HashableDimensionKey to query the
    //                       condition.
    // [allConditions]: all condition trackers. This is needed because the condition evaluation is
    //                  done recursively
    // [isPartialLink]: true if the link specified by 'conditionParameters' contains all the fields
    //                  in the condition tracker output dimension.
    // [conditionCache]: the cache holding the condition evaluation values.
    virtual void isConditionMet(
            const ConditionKey& conditionParameters,
            const std::vector<sp<ConditionTracker>>& allConditions,
            const bool isPartialLink,
            std::vector<ConditionState>& conditionCache) const = 0;

    // return the list of AtomMatchingTracker index that this ConditionTracker uses.
    virtual const std::set<int>& getAtomMatchingTrackerIndex() const {
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

    inline uint64_t getProtoHash() const {
        return mProtoHash;
    }

    virtual const std::map<HashableDimensionKey, int>* getSlicedDimensionMap(
            const std::vector<sp<ConditionTracker>>& allConditions) const = 0;

    virtual bool IsChangedDimensionTrackable() const = 0;

    virtual bool IsSimpleCondition() const = 0;

    virtual bool equalOutputDimensions(
        const std::vector<sp<ConditionTracker>>& allConditions,
        const vector<Matcher>& dimensions) const = 0;

    // Return the current condition state of the unsliced part of the condition.
    inline ConditionState getUnSlicedPartConditionState() const  {
        return mUnSlicedPartCondition;
    }

protected:
    const int64_t mConditionId;

    // the index of this condition in the manager's condition list.
    int mIndex;

    // if it's properly initialized.
    bool mInitialized;

    // the list of AtomMatchingTracker index that this ConditionTracker uses.
    std::set<int> mTrackerIndex;

    // This variable is only used for CombinationConditionTrackers.
    // SimpleConditionTrackers technically don't have an unsliced part because
    // they are either sliced or unsliced.
    //
    // CombinationConditionTrackers have multiple children ConditionTrackers
    // that can be a mixture of sliced or unsliced. This tracks the
    // condition of the unsliced part of the combination condition.
    ConditionState mUnSlicedPartCondition;

    bool mSliced;

    // Hash of the Predicate's proto bytes from StatsdConfig.
    // Used to determine if the definition of this condition has changed across a config update.
    const uint64_t mProtoHash;

    FRIEND_TEST(ConfigUpdateTest, TestUpdateConditions);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
