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

#define LOG_TAG "Stats_SimpleConditionTracker"
#define DEBUG true  // STOPSHIP if true
#define VLOG(...) \
    if (DEBUG) ALOGD(__VA_ARGS__);

#include "SimpleConditionTracker.h"
#include <cutils/log.h>
#include <log/logprint.h>

using std::string;
using std::unique_ptr;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

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
            ALOGW("Stop matcher %s not found in the config", simpleCondition.stop().c_str());
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

bool SimpleConditionTracker::evaluateCondition(const LogEventWrapper& event,
                                               const vector<MatchingState>& eventMatcherValues,
                                               const vector<sp<ConditionTracker>>& mAllConditions,
                                               vector<ConditionState>& conditionCache,
                                               vector<bool>& changedCache) {
    if (conditionCache[mIndex] != ConditionState::kNotEvaluated) {
        // it has been evaluated.
        VLOG("Yes, already evaluated, %s %d", mName.c_str(), mConditionState);
        return false;
    }

    // Ignore nesting, because we know we cannot trust ourselves on tracking nesting conditions.
    ConditionState newCondition = mConditionState;
    // Note: The order to evaluate the following start, stop, stop_all matters.
    // The priority of overwrite is stop_all > stop > start.
    if (mStartLogMatcherIndex >= 0 &&
        eventMatcherValues[mStartLogMatcherIndex] == MatchingState::kMatched) {
        newCondition = ConditionState::kTrue;
    }

    if (mStopLogMatcherIndex >= 0 &&
        eventMatcherValues[mStopLogMatcherIndex] == MatchingState::kMatched) {
        newCondition = ConditionState::kFalse;
    }

    if (mStopAllLogMatcherIndex >= 0 &&
        eventMatcherValues[mStopAllLogMatcherIndex] == MatchingState::kMatched) {
        newCondition = ConditionState::kFalse;
    }

    bool changed = (mConditionState != newCondition);
    mConditionState = newCondition;
    conditionCache[mIndex] = mConditionState;
    changedCache[mIndex] = changed;
    return changed;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
