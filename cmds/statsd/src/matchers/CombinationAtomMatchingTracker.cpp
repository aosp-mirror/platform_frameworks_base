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

#include "Log.h"

#include "CombinationAtomMatchingTracker.h"

#include "matchers/matcher_util.h"

namespace android {
namespace os {
namespace statsd {

using std::set;
using std::unordered_map;
using std::vector;

CombinationAtomMatchingTracker::CombinationAtomMatchingTracker(const int64_t& id, const int index,
                                                               const uint64_t protoHash)
    : AtomMatchingTracker(id, index, protoHash) {
}

CombinationAtomMatchingTracker::~CombinationAtomMatchingTracker() {
}

bool CombinationAtomMatchingTracker::init(
        const vector<AtomMatcher>& allAtomMatchers,
        const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        const unordered_map<int64_t, int>& matcherMap, vector<bool>& stack) {
    if (mInitialized) {
        return true;
    }

    // mark this node as visited in the recursion stack.
    stack[mIndex] = true;

    AtomMatcher_Combination matcher = allAtomMatchers[mIndex].combination();

    // LogicalOperation is missing in the config
    if (!matcher.has_operation()) {
        return false;
    }

    mLogicalOperation = matcher.operation();

    if (mLogicalOperation == LogicalOperation::NOT && matcher.matcher_size() != 1) {
        return false;
    }

    for (const auto& child : matcher.matcher()) {
        auto pair = matcherMap.find(child);
        if (pair == matcherMap.end()) {
            ALOGW("Matcher %lld not found in the config", (long long)child);
            return false;
        }

        int childIndex = pair->second;

        // if the child is a visited node in the recursion -> circle detected.
        if (stack[childIndex]) {
            ALOGE("Circle detected in matcher config");
            return false;
        }

        if (!allAtomMatchingTrackers[childIndex]->init(allAtomMatchers, allAtomMatchingTrackers,
                                                       matcherMap, stack)) {
            ALOGW("child matcher init failed %lld", (long long)child);
            return false;
        }

        mChildren.push_back(childIndex);

        const set<int>& childTagIds = allAtomMatchingTrackers[childIndex]->getAtomIds();
        mAtomIds.insert(childTagIds.begin(), childTagIds.end());
    }

    mInitialized = true;
    // unmark this node in the recursion stack.
    stack[mIndex] = false;
    return true;
}

bool CombinationAtomMatchingTracker::onConfigUpdated(
        const AtomMatcher& matcher, const int index,
        const unordered_map<int64_t, int>& atomMatchingTrackerMap) {
    mIndex = index;
    mChildren.clear();
    AtomMatcher_Combination combinationMatcher = matcher.combination();
    for (const int64_t child : combinationMatcher.matcher()) {
        const auto& pair = atomMatchingTrackerMap.find(child);
        if (pair == atomMatchingTrackerMap.end()) {
            ALOGW("Matcher %lld not found in the config", (long long)child);
            return false;
        }
        mChildren.push_back(pair->second);
    }
    return true;
}

void CombinationAtomMatchingTracker::onLogEvent(
        const LogEvent& event, const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        vector<MatchingState>& matcherResults) {
    // this event has been processed.
    if (matcherResults[mIndex] != MatchingState::kNotComputed) {
        return;
    }

    if (mAtomIds.find(event.GetTagId()) == mAtomIds.end()) {
        matcherResults[mIndex] = MatchingState::kNotMatched;
        return;
    }

    // evaluate children matchers if they haven't been evaluated.
    for (const int childIndex : mChildren) {
        if (matcherResults[childIndex] == MatchingState::kNotComputed) {
            const sp<AtomMatchingTracker>& child = allAtomMatchingTrackers[childIndex];
            child->onLogEvent(event, allAtomMatchingTrackers, matcherResults);
        }
    }

    bool matched = combinationMatch(mChildren, mLogicalOperation, matcherResults);
    matcherResults[mIndex] = matched ? MatchingState::kMatched : MatchingState::kNotMatched;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
