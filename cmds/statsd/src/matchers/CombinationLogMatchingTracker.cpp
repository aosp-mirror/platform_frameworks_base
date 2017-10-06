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

#include "CombinationLogMatchingTracker.h"

#include <cutils/log.h>
#include "matcher_util.h"
using std::set;
using std::string;
using std::unique_ptr;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

CombinationLogMatchingTracker::CombinationLogMatchingTracker(const string& name, const int index)
    : LogMatchingTracker(name, index) {
}

CombinationLogMatchingTracker::~CombinationLogMatchingTracker() {
}

bool CombinationLogMatchingTracker::init(const vector<LogEntryMatcher>& allLogMatchers,
                                         const vector<sp<LogMatchingTracker>>& allTrackers,
                                         const unordered_map<string, int>& matcherMap,
                                         vector<bool>& stack) {
    if (mInitialized) {
        return true;
    }

    // mark this node as visited in the recursion stack.
    stack[mIndex] = true;

    LogEntryMatcher_Combination matcher = allLogMatchers[mIndex].combination();

    // LogicalOperation is missing in the config
    if (!matcher.has_operation()) {
        return false;
    }

    mLogicalOperation = matcher.operation();

    if (mLogicalOperation == LogicalOperation::NOT && matcher.matcher_size() != 1) {
        return false;
    }

    for (const string& child : matcher.matcher()) {
        auto pair = matcherMap.find(child);
        if (pair == matcherMap.end()) {
            ALOGW("Matcher %s not found in the config", child.c_str());
            return false;
        }

        int childIndex = pair->second;

        // if the child is a visited node in the recursion -> circle detected.
        if (stack[childIndex]) {
            ALOGE("Circle detected in matcher config");
            return false;
        }

        if (!allTrackers[childIndex]->init(allLogMatchers, allTrackers, matcherMap, stack)) {
            ALOGW("child matcher init failed %s", child.c_str());
            return false;
        }

        mChildren.push_back(childIndex);

        const set<int>& childTagIds = allTrackers[childIndex]->getTagIds();
        mTagIds.insert(childTagIds.begin(), childTagIds.end());
    }

    mInitialized = true;
    // unmark this node in the recursion stack.
    stack[mIndex] = false;
    return true;
}

void CombinationLogMatchingTracker::onLogEvent(const LogEventWrapper& event,
                                               const vector<sp<LogMatchingTracker>>& allTrackers,
                                               vector<MatchingState>& matcherResults) {
    // this event has been processed.
    if (matcherResults[mIndex] != MatchingState::kNotComputed) {
        return;
    }

    if (mTagIds.find(event.tagId) == mTagIds.end()) {
        matcherResults[mIndex] = MatchingState::kNotMatched;
        return;
    }

    // evaluate children matchers if they haven't been evaluated.
    for (const int childIndex : mChildren) {
        if (matcherResults[childIndex] == MatchingState::kNotComputed) {
            const sp<LogMatchingTracker>& child = allTrackers[childIndex];
            child->onLogEvent(event, allTrackers, matcherResults);
        }
    }

    bool matched = combinationMatch(mChildren, mLogicalOperation, matcherResults);
    matcherResults[mIndex] = matched ? MatchingState::kMatched : MatchingState::kNotMatched;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
