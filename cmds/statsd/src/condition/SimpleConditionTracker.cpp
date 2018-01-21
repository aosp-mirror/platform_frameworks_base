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
#include "guardrail/StatsdStats.h"
#include "dimension.h"

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
        const ConfigKey& key, const int64_t& id, const int index,
        const SimplePredicate& simplePredicate,
        const unordered_map<int64_t, int>& trackerNameIndexMap)
    : ConditionTracker(id, index), mConfigKey(key) {
    VLOG("creating SimpleConditionTracker %lld", (long long)mConditionId);
    mCountNesting = simplePredicate.count_nesting();

    if (simplePredicate.has_start()) {
        auto pair = trackerNameIndexMap.find(simplePredicate.start());
        if (pair == trackerNameIndexMap.end()) {
            ALOGW("Start matcher %lld not found in the config", (long long)simplePredicate.start());
            return;
        }
        mStartLogMatcherIndex = pair->second;
        mTrackerIndex.insert(mStartLogMatcherIndex);
    } else {
        mStartLogMatcherIndex = -1;
    }

    if (simplePredicate.has_stop()) {
        auto pair = trackerNameIndexMap.find(simplePredicate.stop());
        if (pair == trackerNameIndexMap.end()) {
            ALOGW("Stop matcher %lld not found in the config", (long long)simplePredicate.stop());
            return;
        }
        mStopLogMatcherIndex = pair->second;
        mTrackerIndex.insert(mStopLogMatcherIndex);
    } else {
        mStopLogMatcherIndex = -1;
    }

    if (simplePredicate.has_stop_all()) {
        auto pair = trackerNameIndexMap.find(simplePredicate.stop_all());
        if (pair == trackerNameIndexMap.end()) {
            ALOGW("Stop all matcher %lld found in the config", (long long)simplePredicate.stop_all());
            return;
        }
        mStopAllLogMatcherIndex = pair->second;
        mTrackerIndex.insert(mStopAllLogMatcherIndex);
    } else {
        mStopAllLogMatcherIndex = -1;
    }

    mOutputDimensions = simplePredicate.dimensions();

    if (mOutputDimensions.child_size() > 0) {
        mSliced = true;
    }

    if (simplePredicate.initial_value() == SimplePredicate_InitialValue_FALSE) {
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

bool SimpleConditionTracker::init(const vector<Predicate>& allConditionConfig,
                                  const vector<sp<ConditionTracker>>& allConditionTrackers,
                                  const unordered_map<int64_t, int>& conditionIdIndexMap,
                                  vector<bool>& stack) {
    // SimpleConditionTracker does not have dependency on other conditions, thus we just return
    // if the initialization was successful.
    return mInitialized;
}

void print(map<HashableDimensionKey, int>& conditions, const int64_t& id) {
    VLOG("%lld DUMP:", (long long)id);
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

bool SimpleConditionTracker::hitGuardRail(const HashableDimensionKey& newKey) {
    if (!mSliced || mSlicedConditionState.find(newKey) != mSlicedConditionState.end()) {
        // if the condition is not sliced or the key is not new, we are good!
        return false;
    }
    // 1. Report the tuple count if the tuple count > soft limit
    if (mSlicedConditionState.size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
        size_t newTupleCount = mSlicedConditionState.size() + 1;
        StatsdStats::getInstance().noteConditionDimensionSize(mConfigKey, mConditionId, newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
            ALOGE("Predicate %lld dropping data for dimension key %s",
                (long long)mConditionId, newKey.c_str());
            return true;
        }
    }
    return false;
}

void SimpleConditionTracker::handleConditionEvent(const HashableDimensionKey& outputKey,
                                                  bool matchStart,
                                                  std::vector<ConditionState>& conditionCache,
                                                  std::vector<bool>& conditionChangedCache) {
    if ((int)conditionChangedCache.size() <= mIndex) {
        ALOGE("handleConditionEvent: param conditionChangedCache not initialized.");
        return;
    }
    if ((int)conditionCache.size() <= mIndex) {
        ALOGE("handleConditionEvent: param conditionCache not initialized.");
        return;
    }
    bool changed = false;
    auto outputIt = mSlicedConditionState.find(outputKey);
    ConditionState newCondition;
    if (hitGuardRail(outputKey)) {
        conditionChangedCache[mIndex] = false;
        // Tells the caller it's evaluated.
        conditionCache[mIndex] = ConditionState::kUnknown;
        return;
    }
    if (outputIt == mSlicedConditionState.end()) {
        // We get a new output key.
        newCondition = matchStart ? ConditionState::kTrue : ConditionState::kFalse;
        if (matchStart && mInitialValue != ConditionState::kTrue) {
            mSlicedConditionState.insert(std::make_pair(outputKey, 1));
            changed = true;
        } else if (mInitialValue != ConditionState::kFalse) {
            // it's a stop and we don't have history about it.
            // If the default condition is not false, it means this stop is valuable to us.
            mSlicedConditionState.insert(std::make_pair(outputKey, 0));
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
        print(mSlicedConditionState, mConditionId);
    }

    conditionChangedCache[mIndex] = changed;
    conditionCache[mIndex] = newCondition;

    VLOG("SimplePredicate %lld nonSlicedChange? %d", (long long)mConditionId,
         conditionChangedCache[mIndex] == true);
}

void SimpleConditionTracker::evaluateCondition(const LogEvent& event,
                                               const vector<MatchingState>& eventMatcherValues,
                                               const vector<sp<ConditionTracker>>& mAllConditions,
                                               vector<ConditionState>& conditionCache,
                                               vector<bool>& conditionChangedCache) {
    if (conditionCache[mIndex] != ConditionState::kNotEvaluated) {
        // it has been evaluated.
        VLOG("Yes, already evaluated, %lld %d",
            (long long)mConditionId, conditionCache[mIndex]);
        return;
    }

    if (mStopAllLogMatcherIndex >= 0 && mStopAllLogMatcherIndex < int(eventMatcherValues.size()) &&
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
        // The event doesn't match this condition. So we just report existing condition values.
        conditionChangedCache[mIndex] = false;
        if (mSliced) {
            // if the condition result is sliced. metrics won't directly get value from the
            // cache, so just set any value other than kNotEvaluated.
            conditionCache[mIndex] = ConditionState::kUnknown;
        } else {
            const auto& itr = mSlicedConditionState.find(DEFAULT_DIMENSION_KEY);
            if (itr == mSlicedConditionState.end()) {
                // condition not sliced, but we haven't seen the matched start or stop yet. so
                // return initial value.
                conditionCache[mIndex] = mInitialValue;
            } else {
                // return the cached condition.
                conditionCache[mIndex] =
                        itr->second > 0 ? ConditionState::kTrue : ConditionState::kFalse;
            }
        }

        return;
    }

    // outputKey is the output values. e.g, uid:1234
    const std::vector<DimensionsValue> outputValues = getDimensionKeys(event, mOutputDimensions);
    if (outputValues.size() == 0) {
        // The original implementation would generate an empty string dimension hash when condition
        // is not sliced.
        handleConditionEvent(
            DEFAULT_DIMENSION_KEY, matchedState == 1, conditionCache, conditionChangedCache);
    } else if (outputValues.size() == 1) {
        handleConditionEvent(HashableDimensionKey(outputValues[0]), matchedState == 1,
            conditionCache, conditionChangedCache);
    } else {
        // If this event has multiple nodes in the attribution chain,  this log event probably will
        // generate multiple dimensions. If so, we will find if the condition changes for any
        // dimension and ask the corresponding metric producer to verify whether the actual sliced
        // condition has changed or not.
        // A high level assumption is that a predicate is either sliced or unsliced. We will never
        // have both sliced and unsliced version of a predicate.
        for (const DimensionsValue& outputValue : outputValues) {
            vector<ConditionState> dimensionalConditionCache(conditionCache.size(),
                                                             ConditionState::kNotEvaluated);
            vector<bool> dimensionalConditionChangedCache(conditionChangedCache.size(), false);

            handleConditionEvent(HashableDimensionKey(outputValue), matchedState == 1,
                dimensionalConditionCache, dimensionalConditionChangedCache);

            OrConditionState(dimensionalConditionCache, &conditionCache);
            OrBooleanVector(dimensionalConditionChangedCache, &conditionChangedCache);
        }
    }
}

void SimpleConditionTracker::isConditionMet(
        const ConditionKey& conditionParameters,
        const vector<sp<ConditionTracker>>& allConditions,
        vector<ConditionState>& conditionCache) const {
    const auto pair = conditionParameters.find(mConditionId);

    if (pair == conditionParameters.end() && mOutputDimensions.child_size() > 0) {
        ALOGE("Predicate %lld output has dimension, but it's not specified in the query!",
              (long long)mConditionId);
        conditionCache[mIndex] = mInitialValue;
        return;
    }
    std::vector<HashableDimensionKey> defaultKeys = {DEFAULT_DIMENSION_KEY};
    const std::vector<HashableDimensionKey> &keys =
            (pair == conditionParameters.end()) ? defaultKeys : pair->second;

    ConditionState conditionState = ConditionState::kNotEvaluated;
    for (const auto& key : keys) {
        auto startedCountIt = mSlicedConditionState.find(key);
        if (startedCountIt != mSlicedConditionState.end()) {
            conditionState = conditionState |
                    (startedCountIt->second > 0 ? ConditionState::kTrue : ConditionState::kFalse);
        } else {
            // For unseen key, check whether the require dimensions are subset of sliced condition
            // output.
            bool seenDimension = false;
            for (const auto& slice : mSlicedConditionState) {
                if (IsSubDimension(slice.first.getDimensionsValue(),
                                   key.getDimensionsValue())) {
                    seenDimension = true;
                    conditionState = conditionState |
                        (slice.second > 0 ? ConditionState::kTrue : ConditionState::kFalse);
                }
                if (conditionState == ConditionState::kTrue) {
                    break;
                }
            }
            if (!seenDimension) {
                conditionState = conditionState | mInitialValue;
            }
        }
    }
    conditionCache[mIndex] = conditionState;
    VLOG("Predicate %lld return %d", (long long)mConditionId, conditionCache[mIndex]);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
