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
#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "StateConditionTracker.h"
#include "guardrail/StatsdStats.h"

namespace android {
namespace os {
namespace statsd {

using std::string;
using std::unordered_set;
using std::vector;

StateConditionTracker::StateConditionTracker(const ConfigKey& key, const int64_t& id, const int index,
                           const SimplePredicate& simplePredicate,
                           const unordered_map<int64_t, int>& trackerNameIndexMap,
                           const vector<Matcher> primaryKeys)
    : ConditionTracker(id, index), mConfigKey(key), mPrimaryKeys(primaryKeys) {
    if (simplePredicate.has_start()) {
        auto pair = trackerNameIndexMap.find(simplePredicate.start());
        if (pair == trackerNameIndexMap.end()) {
            ALOGW("Start matcher %lld not found in the config", (long long)simplePredicate.start());
            return;
        }
        mStartLogMatcherIndex = pair->second;
        mTrackerIndex.insert(mStartLogMatcherIndex);
    } else {
        ALOGW("Condition %lld must have a start matcher", (long long)id);
        return;
    }

    if (simplePredicate.has_dimensions()) {
        translateFieldMatcher(simplePredicate.dimensions(), &mOutputDimensions);
        if (mOutputDimensions.size() > 0) {
            mSliced = true;
            mDimensionTag = mOutputDimensions[0].mMatcher.getTag();
        } else {
            ALOGW("Condition %lld has invalid dimensions", (long long)id);
            return;
        }
    } else {
        ALOGW("Condition %lld being a state tracker, but has no dimension", (long long)id);
        return;
    }

    if (simplePredicate.initial_value() == SimplePredicate_InitialValue_FALSE) {
        mInitialValue = ConditionState::kFalse;
    } else {
        mInitialValue = ConditionState::kUnknown;
    }

    mNonSlicedConditionState = mInitialValue;
    mInitialized = true;
}

StateConditionTracker::~StateConditionTracker() {
    VLOG("~StateConditionTracker()");
}

bool StateConditionTracker::init(const vector<Predicate>& allConditionConfig,
                        const vector<sp<ConditionTracker>>& allConditionTrackers,
                        const unordered_map<int64_t, int>& conditionIdIndexMap,
                        vector<bool>& stack) {
    return mInitialized;
}

void StateConditionTracker::dumpState() {
    VLOG("StateConditionTracker %lld DUMP:", (long long)mConditionId);
    for (const auto& value : mSlicedState) {
        VLOG("\t%s -> %s", value.first.toString().c_str(), value.second.toString().c_str());
    }
    VLOG("Last Changed to True: ");
    for (const auto& value : mLastChangedToTrueDimensions) {
        VLOG("%s", value.toString().c_str());
    }
    VLOG("Last Changed to False: ");
    for (const auto& value : mLastChangedToFalseDimensions) {
        VLOG("%s", value.toString().c_str());
    }
}

bool StateConditionTracker::hitGuardRail(const HashableDimensionKey& newKey) {
    if (mSlicedState.find(newKey) != mSlicedState.end()) {
        // if the condition is not sliced or the key is not new, we are good!
        return false;
    }
    // 1. Report the tuple count if the tuple count > soft limit
    if (mSlicedState.size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
        size_t newTupleCount = mSlicedState.size() + 1;
        StatsdStats::getInstance().noteConditionDimensionSize(mConfigKey, mConditionId, newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
            ALOGE("Predicate %lld dropping data for dimension key %s",
                (long long)mConditionId, newKey.toString().c_str());
            return true;
        }
    }
    return false;
}

void StateConditionTracker::evaluateCondition(const LogEvent& event,
                                     const vector<MatchingState>& eventMatcherValues,
                                     const vector<sp<ConditionTracker>>& mAllConditions,
                                     vector<ConditionState>& conditionCache,
                                     vector<bool>& conditionChangedCache) {
    mLastChangedToTrueDimensions.clear();
    mLastChangedToFalseDimensions.clear();
    if (conditionCache[mIndex] != ConditionState::kNotEvaluated) {
        // it has been evaluated.
        VLOG("Yes, already evaluated, %lld %d", (long long)mConditionId, conditionCache[mIndex]);
        return;
    }

    if (mStartLogMatcherIndex >= 0 &&
        eventMatcherValues[mStartLogMatcherIndex] != MatchingState::kMatched) {
        conditionCache[mIndex] =
                mSlicedState.size() > 0 ? ConditionState::kTrue : ConditionState::kFalse;
        conditionChangedCache[mIndex] = false;
        return;
    }

    VLOG("StateConditionTracker evaluate event %s", event.ToString().c_str());

    // Primary key can exclusive fields must be simple fields. so there won't be more than
    // one keys matched.
    HashableDimensionKey primaryKey;
    HashableDimensionKey state;
    if ((mPrimaryKeys.size() > 0 && !filterValues(mPrimaryKeys, event.getValues(), &primaryKey)) ||
        !filterValues(mOutputDimensions, event.getValues(), &state)) {
        ALOGE("Failed to filter fields in the event?? panic now!");
        conditionCache[mIndex] =
                mSlicedState.size() > 0 ? ConditionState::kTrue : ConditionState::kFalse;
        conditionChangedCache[mIndex] = false;
        return;
    }
    hitGuardRail(primaryKey);

    VLOG("StateConditionTracker: key %s state %s", primaryKey.toString().c_str(), state.toString().c_str());

    auto it = mSlicedState.find(primaryKey);
    if (it == mSlicedState.end()) {
        mSlicedState[primaryKey] = state;
        conditionCache[mIndex] = ConditionState::kTrue;
        mLastChangedToTrueDimensions.insert(state);
        conditionChangedCache[mIndex] = true;
    } else if (!(it->second == state)) {
        mLastChangedToFalseDimensions.insert(it->second);
        mLastChangedToTrueDimensions.insert(state);
        mSlicedState[primaryKey] = state;
        conditionCache[mIndex] = ConditionState::kTrue;
        conditionChangedCache[mIndex] = true;
    } else {
        conditionCache[mIndex] = ConditionState::kTrue;
        conditionChangedCache[mIndex] = false;
    }

    if (DEBUG) {
        dumpState();
    }
    return;
}

void StateConditionTracker::isConditionMet(
        const ConditionKey& conditionParameters, const vector<sp<ConditionTracker>>& allConditions,
        const bool isPartialLink,
        vector<ConditionState>& conditionCache) const {
    if (conditionCache[mIndex] != ConditionState::kNotEvaluated) {
        // it has been evaluated.
        VLOG("Yes, already evaluated, %lld %d", (long long)mConditionId, conditionCache[mIndex]);
        return;
    }

    const auto pair = conditionParameters.find(mConditionId);
    if (pair == conditionParameters.end()) {
        if (mSlicedState.size() > 0) {
            conditionCache[mIndex] = ConditionState::kTrue;
        } else {
            conditionCache[mIndex] = ConditionState::kUnknown;
        }
        return;
    }

    const auto& primaryKey = pair->second;
    conditionCache[mIndex] = mInitialValue;
    auto it = mSlicedState.find(primaryKey);
    if (it != mSlicedState.end()) {
        conditionCache[mIndex] = ConditionState::kTrue;
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
