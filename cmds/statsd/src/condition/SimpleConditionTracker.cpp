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

#include "SimpleConditionTracker.h"

#include <log/logprint.h>

namespace android {
namespace os {
namespace statsd {

using std::map;
using std::string;
using std::unique_ptr;
using std::unordered_map;
using std::vector;

SimpleConditionTracker::SimpleConditionTracker(
        const string& name, const int index, const SimpleCondition& simpleCondition,
        const unordered_map<string, int>& trackerNameIndexMap)
    : ConditionTracker(name, index) {
    VLOG("creating SimpleConditionTracker %s", mName.c_str());
    mCountNesting = simpleCondition.count_nesting();

    if (simpleCondition.has_start()) {
        auto pair = trackerNameIndexMap.find(simpleCondition.start());
        if (pair == trackerNameIndexMap.end()) {
            ALOGW("Start matcher %s not found in the config", simpleCondition.start().c_str());
            return;
        }
        mStartLogMatcherIndex = pair->second;
        mTrackerIndex.insert(mStartLogMatcherIndex);
    } else {
        mStartLogMatcherIndex = -1;
    }

    if (simpleCondition.has_stop()) {
        auto pair = trackerNameIndexMap.find(simpleCondition.stop());
        if (pair == trackerNameIndexMap.end()) {
            ALOGW("Stop matcher %s not found in the config", simpleCondition.stop().c_str());
            return;
        }
        mStopLogMatcherIndex = pair->second;
        mTrackerIndex.insert(mStopLogMatcherIndex);
    } else {
        mStopLogMatcherIndex = -1;
    }

    if (simpleCondition.has_stop_all()) {
        auto pair = trackerNameIndexMap.find(simpleCondition.stop_all());
        if (pair == trackerNameIndexMap.end()) {
            ALOGW("Stop all matcher %s not found in the config", simpleCondition.stop().c_str());
            return;
        }
        mStopAllLogMatcherIndex = pair->second;
        mTrackerIndex.insert(mStopAllLogMatcherIndex);
    } else {
        mStopAllLogMatcherIndex = -1;
    }

    mInitialized = true;
}

SimpleConditionTracker::~SimpleConditionTracker() {
    VLOG("~SimpleConditionTracker()");
}

bool SimpleConditionTracker::init(const vector<Condition>& allConditionConfig,
                                  const vector<sp<ConditionTracker>>& allConditionTrackers,
                                  const unordered_map<string, int>& conditionNameIndexMap,
                                  vector<bool>& stack) {
    // SimpleConditionTracker does not have dependency on other conditions, thus we just return
    // if the initialization was successful.
    return mInitialized;
}

void print(unordered_map<HashableDimensionKey, ConditionState>& conditions, const string& name) {
    VLOG("%s DUMP:", name.c_str());

    for (const auto& pair : conditions) {
        VLOG("\t%s %d", pair.first.c_str(), pair.second);
    }
}

void SimpleConditionTracker::addDimensions(const std::vector<KeyMatcher>& keyMatchers) {
    VLOG("Added dimensions size %lu", (unsigned long)keyMatchers.size());
    mDimensionsList.push_back(keyMatchers);
    mSliced = true;
}

bool SimpleConditionTracker::evaluateCondition(const LogEvent& event,
                                               const vector<MatchingState>& eventMatcherValues,
                                               const vector<sp<ConditionTracker>>& mAllConditions,
                                               vector<ConditionState>& conditionCache,
                                               vector<bool>& nonSlicedConditionChanged,
                                               std::vector<bool>& slicedConditionChanged) {
    if (conditionCache[mIndex] != ConditionState::kNotEvaluated) {
        // it has been evaluated.
        VLOG("Yes, already evaluated, %s %d", mName.c_str(), mNonSlicedConditionState);
        return false;
    }

    // Ignore nesting, because we know we cannot trust ourselves on tracking nesting conditions.

    ConditionState newCondition = mNonSlicedConditionState;
    bool matched = false;
    // Note: The order to evaluate the following start, stop, stop_all matters.
    // The priority of overwrite is stop_all > stop > start.
    if (mStartLogMatcherIndex >= 0 &&
        eventMatcherValues[mStartLogMatcherIndex] == MatchingState::kMatched) {
        matched = true;
        newCondition = ConditionState::kTrue;
    }

    if (mStopLogMatcherIndex >= 0 &&
        eventMatcherValues[mStopLogMatcherIndex] == MatchingState::kMatched) {
        matched = true;
        newCondition = ConditionState::kFalse;
    }

    bool stopAll = false;
    if (mStopAllLogMatcherIndex >= 0 &&
        eventMatcherValues[mStopAllLogMatcherIndex] == MatchingState::kMatched) {
        matched = true;
        newCondition = ConditionState::kFalse;
        stopAll = true;
    }

    if (matched == false) {
        slicedConditionChanged[mIndex] = false;
        nonSlicedConditionChanged[mIndex] = false;
        conditionCache[mIndex] = mNonSlicedConditionState;
        return false;
    }

    bool nonSlicedChanged = mNonSlicedConditionState != newCondition;

    bool slicedChanged = false;

    if (stopAll) {
        // TODO: handle stop all; all dimension should be cleared.
    }

    if (mDimensionsList.size() > 0) {
        for (size_t i = 0; i < mDimensionsList.size(); i++) {
            const auto& dim = mDimensionsList[i];
            vector<KeyValuePair> key = getDimensionKey(event, dim);
            HashableDimensionKey hashableKey = getHashableKey(key);
            if (mSlicedConditionState.find(hashableKey) == mSlicedConditionState.end() ||
                mSlicedConditionState[hashableKey] != newCondition) {
                slicedChanged = true;
                mSlicedConditionState[hashableKey] = newCondition;
            }
            VLOG("key: %s %d", hashableKey.c_str(), newCondition);
        }
        // dump all dimensions for debugging
        if (DEBUG) {
            print(mSlicedConditionState, mName);
        }
    }

    // even if this SimpleCondition is not sliced, it may be part of a sliced CombinationCondition
    // if the nonSliced condition changed, it may affect the sliced condition in the parent node.
    // so mark the slicedConditionChanged to be true.
    // For example: APP_IN_BACKGROUND_OR_SCREEN_OFF
    //     APP_IN_BACKGROUND is sliced [App_A->True, App_B->False].
    //     SCREEN_OFF is not sliced, and it changes from False -> True;
    //     We need to populate this change to parent condition. Because for App_B,
    //     the APP_IN_BACKGROUND_OR_SCREEN_OFF condition would change from False->True.
    slicedConditionChanged[mIndex] = mSliced ? slicedChanged : nonSlicedChanged;
    nonSlicedConditionChanged[mIndex] = nonSlicedChanged;

    VLOG("SimpleCondition %s nonSlicedChange? %d  SlicedChanged? %d", mName.c_str(),
         nonSlicedConditionChanged[mIndex] == true, slicedConditionChanged[mIndex] == true);
    mNonSlicedConditionState = newCondition;
    conditionCache[mIndex] = mNonSlicedConditionState;

    return nonSlicedConditionChanged[mIndex];
}

void SimpleConditionTracker::isConditionMet(
        const map<string, HashableDimensionKey>& conditionParameters,
        const vector<sp<ConditionTracker>>& allConditions, vector<ConditionState>& conditionCache) {
    const auto pair = conditionParameters.find(mName);
    if (pair == conditionParameters.end()) {
        // the query does not need my sliced condition. just return the non sliced condition.
        conditionCache[mIndex] = mNonSlicedConditionState;
        VLOG("Condition %s return %d", mName.c_str(), mNonSlicedConditionState);
        return;
    }

    const HashableDimensionKey& key = pair->second;
    VLOG("simpleCondition %s query key: %s", mName.c_str(), key.c_str());

    if (mSlicedConditionState.find(key) == mSlicedConditionState.end()) {
        // never seen this key before. the condition is unknown to us.
        conditionCache[mIndex] = ConditionState::kUnknown;
    } else {
        conditionCache[mIndex] = mSlicedConditionState[key];
    }

    VLOG("Condition %s return %d", mName.c_str(), conditionCache[mIndex]);

    if (DEBUG) {
        print(mSlicedConditionState, mName);
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
