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

#define DEBUG true  // STOPSHIP if true
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

CombinationConditionTracker::CombinationConditionTracker(const string& name, const int index)
    : ConditionTracker(name, index) {
    VLOG("creating CombinationConditionTracker %s", mName.c_str());
}

CombinationConditionTracker::~CombinationConditionTracker() {
    VLOG("~CombinationConditionTracker() %s", mName.c_str());
}

bool CombinationConditionTracker::init(const vector<Condition>& allConditionConfig,
                                       const vector<sp<ConditionTracker>>& allConditionTrackers,
                                       const unordered_map<string, int>& conditionNameIndexMap,
                                       vector<bool>& stack) {
    VLOG("Combiniation condition init() %s", mName.c_str());
    if (mInitialized) {
        return true;
    }

    // mark this node as visited in the recursion stack.
    stack[mIndex] = true;

    Condition_Combination combinationCondition = allConditionConfig[mIndex].combination();

    if (!combinationCondition.has_operation()) {
        return false;
    }
    mLogicalOperation = combinationCondition.operation();

    if (mLogicalOperation == LogicalOperation::NOT && combinationCondition.condition_size() != 1) {
        return false;
    }

    for (string child : combinationCondition.condition()) {
        auto it = conditionNameIndexMap.find(child);

        if (it == conditionNameIndexMap.end()) {
            ALOGW("Condition %s not found in the config", child.c_str());
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
                                                     conditionNameIndexMap, stack);

        if (!initChildSucceeded) {
            ALOGW("Child initialization failed %s ", child.c_str());
            return false;
        } else {
            ALOGW("Child initialization success %s ", child.c_str());
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
        const map<string, HashableDimensionKey>& conditionParameters,
        const vector<sp<ConditionTracker>>& allConditions, vector<ConditionState>& conditionCache) {
    for (const int childIndex : mChildren) {
        if (conditionCache[childIndex] == ConditionState::kNotEvaluated) {
            allConditions[childIndex]->isConditionMet(conditionParameters, allConditions,
                                                      conditionCache);
        }
    }
    conditionCache[mIndex] =
            evaluateCombinationCondition(mChildren, mLogicalOperation, conditionCache);
}

bool CombinationConditionTracker::evaluateCondition(
        const LogEvent& event, const std::vector<MatchingState>& eventMatcherValues,
        const std::vector<sp<ConditionTracker>>& mAllConditions,
        std::vector<ConditionState>& nonSlicedConditionCache,
        std::vector<bool>& nonSlicedChangedCache, vector<bool>& slicedConditionChanged) {
    // value is up to date.
    if (nonSlicedConditionCache[mIndex] != ConditionState::kNotEvaluated) {
        return false;
    }

    for (const int childIndex : mChildren) {
        if (nonSlicedConditionCache[childIndex] == ConditionState::kNotEvaluated) {
            const sp<ConditionTracker>& child = mAllConditions[childIndex];
            child->evaluateCondition(event, eventMatcherValues, mAllConditions,
                                     nonSlicedConditionCache, nonSlicedChangedCache,
                                     slicedConditionChanged);
        }
    }

    ConditionState newCondition =
            evaluateCombinationCondition(mChildren, mLogicalOperation, nonSlicedConditionCache);

    bool nonSlicedChanged = (mNonSlicedConditionState != newCondition);
    mNonSlicedConditionState = newCondition;

    nonSlicedConditionCache[mIndex] = mNonSlicedConditionState;

    nonSlicedChangedCache[mIndex] = nonSlicedChanged;

    if (mSliced) {
        for (const int childIndex : mChildren) {
            // If any of the sliced condition in children condition changes, the combination
            // condition may be changed too.
            if (slicedConditionChanged[childIndex]) {
                slicedConditionChanged[mIndex] = true;
                break;
            }
        }
        ALOGD("CombinationCondition %s sliced may changed? %d", mName.c_str(),
              slicedConditionChanged[mIndex] == true);
    }

    return nonSlicedChanged;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
