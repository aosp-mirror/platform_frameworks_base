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

    mOutputDimension.insert(mOutputDimension.begin(), simpleCondition.dimension().begin(),
                            simpleCondition.dimension().end());

    if (mOutputDimension.size() > 0) {
        mSliced = true;
    }

    if (simpleCondition.initial_value() == SimpleCondition_InitialValue_FALSE) {
        mInitialValue = ConditionState::kFalse;
    } else {
        mInitialValue = ConditionState::kUnknown;
    }

    mNonSlicedConditionState = mInitialValue;

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

void print(map<HashableDimensionKey, int>& conditions, const string& name) {
    VLOG("%s DUMP:", name.c_str());
    for (const auto& pair : conditions) {
        VLOG("\t%s : %d", pair.first.c_str(), pair.second);
    }
}

void SimpleConditionTracker::handleStopAll(std::vector<ConditionState>& conditionCache,
                                           std::vector<bool>& conditionChangedCache) {
    // Unless the default condition is false, and there was nothing started, otherwise we have
    // triggered a condition change.
    conditionChangedCache[mIndex] =
            (mInitialValue == ConditionState::kFalse && mSlicedConditionState.empty()) ? false
                                                                                           : true;

    // After StopAll, we know everything has stopped. From now on, default condition is false.
    mInitialValue = ConditionState::kFalse;
    mSlicedConditionState.clear();
    conditionCache[mIndex] = ConditionState::kFalse;
}

void SimpleConditionTracker::handleConditionEvent(const HashableDimensionKey& outputKey,
                                                  bool matchStart,
                                                  std::vector<ConditionState>& conditionCache,
                                                  std::vector<bool>& conditionChangedCache) {
    bool changed = false;
    auto outputIt = mSlicedConditionState.find(outputKey);
    ConditionState newCondition;
    if (outputIt == mSlicedConditionState.end()) {
        // We get a new output key.
        newCondition = matchStart ? ConditionState::kTrue : ConditionState::kFalse;
        if (matchStart && mInitialValue != ConditionState::kTrue) {
            mSlicedConditionState[outputKey] = 1;
            changed = true;
        } else if (mInitialValue != ConditionState::kFalse) {
            // it's a stop and we don't have history about it.
            // If the default condition is not false, it means this stop is valuable to us.
            mSlicedConditionState[outputKey] = 0;
            changed = true;
        }
    } else {
        // we have history about this output key.
        auto& startedCount = outputIt->second;
        // assign the old value first.
        newCondition = startedCount > 0 ? ConditionState::kTrue : ConditionState::kFalse;
        if (matchStart) {
            if (startedCount == 0) {
                // This condition for this output key will change from false -> true
                changed = true;
            }

            // it's ok to do ++ here, even if we don't count nesting. The >1 counts will be treated
            // as 1 if not counting nesting.
            startedCount++;
            newCondition = ConditionState::kTrue;
        } else {
            // This is a stop event.
            if (startedCount > 0) {
                if (mCountNesting) {
                    startedCount--;
                    if (startedCount == 0) {
                        newCondition = ConditionState::kFalse;
                    }
                } else {
                    // not counting nesting, so ignore the number of starts, stop now.
                    startedCount = 0;
                    newCondition = ConditionState::kFalse;
                }
                // if everything has stopped for this output key, condition true -> false;
                if (startedCount == 0) {
                    changed = true;
                }
            }

            // if default condition is false, it means we don't need to keep the false values.
            if (mInitialValue == ConditionState::kFalse && startedCount == 0) {
                mSlicedConditionState.erase(outputIt);
                VLOG("erase key %s", outputKey.c_str());
            }
        }
    }

    // dump all dimensions for debugging
    if (DEBUG) {
        print(mSlicedConditionState, mName);
    }

    conditionChangedCache[mIndex] = changed;
    conditionCache[mIndex] = newCondition;

    VLOG("SimpleCondition %s nonSlicedChange? %d", mName.c_str(),
         conditionChangedCache[mIndex] == true);
}

void SimpleConditionTracker::evaluateCondition(const LogEvent& event,
                                               const vector<MatchingState>& eventMatcherValues,
                                               const vector<sp<ConditionTracker>>& mAllConditions,
                                               vector<ConditionState>& conditionCache,
                                               vector<bool>& conditionChangedCache) {
    if (conditionCache[mIndex] != ConditionState::kNotEvaluated) {
        // it has been evaluated.
        VLOG("Yes, already evaluated, %s %d", mName.c_str(), mNonSlicedConditionState);
        return;
    }

    if (mStopAllLogMatcherIndex >= 0 &&
        eventMatcherValues[mStopAllLogMatcherIndex] == MatchingState::kMatched) {
        handleStopAll(conditionCache, conditionChangedCache);
        return;
    }

    int matchedState = -1;
    // Note: The order to evaluate the following start, stop, stop_all matters.
    // The priority of overwrite is stop_all > stop > start.
    if (mStartLogMatcherIndex >= 0 &&
        eventMatcherValues[mStartLogMatcherIndex] == MatchingState::kMatched) {
        matchedState = 1;
    }

    if (mStopLogMatcherIndex >= 0 &&
        eventMatcherValues[mStopLogMatcherIndex] == MatchingState::kMatched) {
        matchedState = 0;
    }

    if (matchedState < 0) {
        conditionChangedCache[mIndex] = false;
        conditionCache[mIndex] = mNonSlicedConditionState;
        return;
    }

    // outputKey is the output key values. e.g, uid:1234
    const HashableDimensionKey outputKey = getHashableKey(getDimensionKey(event, mOutputDimension));
    handleConditionEvent(outputKey, matchedState == 1, conditionCache, conditionChangedCache);
}

void SimpleConditionTracker::isConditionMet(
        const map<string, HashableDimensionKey>& conditionParameters,
        const vector<sp<ConditionTracker>>& allConditions, vector<ConditionState>& conditionCache) {
    const auto pair = conditionParameters.find(mName);
    HashableDimensionKey key =
            (pair == conditionParameters.end()) ? DEFAULT_DIMENSION_KEY : pair->second;

    if (pair == conditionParameters.end() && mOutputDimension.size() > 0) {
        ALOGE("Condition %s output has dimension, but it's not specified in the query!",
              mName.c_str());
        conditionCache[mIndex] = mInitialValue;
        return;
    }

    VLOG("simpleCondition %s query key: %s", mName.c_str(), key.c_str());

    auto startedCountIt = mSlicedConditionState.find(key);
    if (startedCountIt == mSlicedConditionState.end()) {
        conditionCache[mIndex] = mInitialValue;
    } else {
        conditionCache[mIndex] =
                startedCountIt->second > 0 ? ConditionState::kTrue : ConditionState::kFalse;
    }

    VLOG("Condition %s return %d", mName.c_str(), conditionCache[mIndex]);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
