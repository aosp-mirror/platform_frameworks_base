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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"
#include "CombinationConditionTracker.h"

#include <log/logprint.h>

namespace android {
namespace os {
namespace statsd {

using std::map;
using std::string;
using std::unique_ptr;
using std::unordered_map;
using std::vector;

CombinationConditionTracker::CombinationConditionTracker(const int64_t& id, const int index)
    : ConditionTracker(id, index) {
    VLOG("creating CombinationConditionTracker %lld", (long long)mConditionId);
}

CombinationConditionTracker::~CombinationConditionTracker() {
    VLOG("~CombinationConditionTracker() %lld", (long long)mConditionId);
}

bool CombinationConditionTracker::init(const vector<Predicate>& allConditionConfig,
                                       const vector<sp<ConditionTracker>>& allConditionTrackers,
                                       const unordered_map<int64_t, int>& conditionIdIndexMap,
                                       vector<bool>& stack) {
    VLOG("Combination predicate init() %lld", (long long)mConditionId);
    if (mInitialized) {
        return true;
    }

    // mark this node as visited in the recursion stack.
    stack[mIndex] = true;

    Predicate_Combination combinationCondition = allConditionConfig[mIndex].combination();

    if (!combinationCondition.has_operation()) {
        return false;
    }
    mLogicalOperation = combinationCondition.operation();

    if (mLogicalOperation == LogicalOperation::NOT && combinationCondition.predicate_size() != 1) {
        return false;
    }

    for (auto child : combinationCondition.predicate()) {
        auto it = conditionIdIndexMap.find(child);

        if (it == conditionIdIndexMap.end()) {
            ALOGW("Predicate %lld not found in the config", (long long)child);
            return false;
        }

        int childIndex = it->second;
        const auto& childTracker = allConditionTrackers[childIndex];
        // if the child is a visited node in the recursion -> circle detected.
        if (stack[childIndex]) {
            ALOGW("Circle detected!!!");
            return false;
        }


        bool initChildSucceeded = childTracker->init(allConditionConfig, allConditionTrackers,
                                                     conditionIdIndexMap, stack);

        if (!initChildSucceeded) {
            ALOGW("Child initialization failed %lld ", (long long)child);
            return false;
        } else {
            ALOGW("Child initialization success %lld ", (long long)child);
        }

        if (allConditionTrackers[childIndex]->isSliced()) {
            setSliced(true);
            mSlicedChildren.push_back(childIndex);
        } else {
            mUnSlicedChildren.push_back(childIndex);
        }
        mChildren.push_back(childIndex);
        mTrackerIndex.insert(childTracker->getLogTrackerIndex().begin(),
                             childTracker->getLogTrackerIndex().end());
    }

    // unmark this node in the recursion stack.
    stack[mIndex] = false;

    mInitialized = true;

    return true;
}

void CombinationConditionTracker::isConditionMet(
        const ConditionKey& conditionParameters, const vector<sp<ConditionTracker>>& allConditions,
        const std::vector<Matcher>& dimensionFields,
        const bool isSubOutputDimensionFields,
        const bool isPartialLink,
        vector<ConditionState>& conditionCache,
        std::unordered_set<HashableDimensionKey>& dimensionsKeySet) const {
    // So far, this is fine as there is at most one child having sliced output.
    for (const int childIndex : mChildren) {
        if (conditionCache[childIndex] == ConditionState::kNotEvaluated) {
            allConditions[childIndex]->isConditionMet(conditionParameters, allConditions,
                                                      dimensionFields,
                                                      isSubOutputDimensionFields,
                                                      isPartialLink,
                                                      conditionCache,
                                                      dimensionsKeySet);
        }
    }
    conditionCache[mIndex] =
            evaluateCombinationCondition(mChildren, mLogicalOperation, conditionCache);
}

void CombinationConditionTracker::evaluateCondition(
        const LogEvent& event, const std::vector<MatchingState>& eventMatcherValues,
        const std::vector<sp<ConditionTracker>>& mAllConditions,
        std::vector<ConditionState>& nonSlicedConditionCache,
        std::vector<bool>& conditionChangedCache) {
    // value is up to date.
    if (nonSlicedConditionCache[mIndex] != ConditionState::kNotEvaluated) {
        return;
    }

    for (const int childIndex : mChildren) {
        // So far, this is fine as there is at most one child having sliced output.
        if (nonSlicedConditionCache[childIndex] == ConditionState::kNotEvaluated) {
            const sp<ConditionTracker>& child = mAllConditions[childIndex];
            child->evaluateCondition(event, eventMatcherValues, mAllConditions,
                                     nonSlicedConditionCache, conditionChangedCache);
        }
    }

    ConditionState newCondition =
            evaluateCombinationCondition(mChildren, mLogicalOperation, nonSlicedConditionCache);
    if (!mSliced) {

        bool nonSlicedChanged = (mNonSlicedConditionState != newCondition);
        mNonSlicedConditionState = newCondition;

        nonSlicedConditionCache[mIndex] = mNonSlicedConditionState;

        conditionChangedCache[mIndex] = nonSlicedChanged;
        mUnSlicedPart = newCondition;
    } else {
        mUnSlicedPart = evaluateCombinationCondition(
            mUnSlicedChildren, mLogicalOperation, nonSlicedConditionCache);

        for (const int childIndex : mChildren) {
            // If any of the sliced condition in children condition changes, the combination
            // condition may be changed too.
            if (conditionChangedCache[childIndex]) {
                conditionChangedCache[mIndex] = true;
                break;
            }
        }
        nonSlicedConditionCache[mIndex] = newCondition;
        VLOG("CombinationPredicate %lld sliced may changed? %d", (long long)mConditionId,
            conditionChangedCache[mIndex] == true);
    }
}

ConditionState CombinationConditionTracker::getMetConditionDimension(
        const std::vector<sp<ConditionTracker>>& allConditions,
        const std::vector<Matcher>& dimensionFields,
        const bool isSubOutputDimensionFields,
        std::unordered_set<HashableDimensionKey>& dimensionsKeySet) const {
    vector<ConditionState> conditionCache(allConditions.size(), ConditionState::kNotEvaluated);
    // So far, this is fine as there is at most one child having sliced output.
    for (const int childIndex : mChildren) {
        conditionCache[childIndex] = conditionCache[childIndex] |
            allConditions[childIndex]->getMetConditionDimension(
                allConditions, dimensionFields, isSubOutputDimensionFields, dimensionsKeySet);
    }
    evaluateCombinationCondition(mChildren, mLogicalOperation, conditionCache);
    if (conditionCache[mIndex] == ConditionState::kTrue && dimensionsKeySet.empty()) {
        dimensionsKeySet.insert(DEFAULT_DIMENSION_KEY);
    }
    return conditionCache[mIndex];
}

bool CombinationConditionTracker::equalOutputDimensions(
        const std::vector<sp<ConditionTracker>>& allConditions,
        const vector<Matcher>& dimensions) const {
    if (mSlicedChildren.size() != 1 ||
        mSlicedChildren.front() >= (int)allConditions.size() ||
        mLogicalOperation != LogicalOperation::AND) {
        return false;
    }
    const sp<ConditionTracker>& slicedChild = allConditions.at(mSlicedChildren.front());
    return slicedChild->equalOutputDimensions(allConditions, dimensions);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
