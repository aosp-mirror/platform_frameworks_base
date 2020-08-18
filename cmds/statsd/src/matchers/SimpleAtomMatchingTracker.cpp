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

#include "SimpleAtomMatchingTracker.h"

namespace android {
namespace os {
namespace statsd {

using std::unordered_map;
using std::vector;

SimpleAtomMatchingTracker::SimpleAtomMatchingTracker(const int64_t& id, const int index,
                                                     const uint64_t protoHash,
                                                     const SimpleAtomMatcher& matcher,
                                                     const sp<UidMap>& uidMap)
    : AtomMatchingTracker(id, index, protoHash), mMatcher(matcher), mUidMap(uidMap) {
    if (!matcher.has_atom_id()) {
        mInitialized = false;
    } else {
        mAtomIds.insert(matcher.atom_id());
        mInitialized = true;
    }
}

SimpleAtomMatchingTracker::~SimpleAtomMatchingTracker() {
}

bool SimpleAtomMatchingTracker::init(const vector<AtomMatcher>& allAtomMatchers,
                                     const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
                                     const unordered_map<int64_t, int>& matcherMap,
                                     vector<bool>& stack) {
    // no need to do anything.
    return mInitialized;
}

bool SimpleAtomMatchingTracker::onConfigUpdated(
        const AtomMatcher& matcher, const int index,
        const unordered_map<int64_t, int>& atomMatchingTrackerMap) {
    mIndex = index;
    // Do not need to update mMatcher since the matcher must be identical across the update.
    return mInitialized;
}

void SimpleAtomMatchingTracker::onLogEvent(
        const LogEvent& event, const vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
        vector<MatchingState>& matcherResults) {
    if (matcherResults[mIndex] != MatchingState::kNotComputed) {
        VLOG("Matcher %lld already evaluated ", (long long)mId);
        return;
    }

    if (mAtomIds.find(event.GetTagId()) == mAtomIds.end()) {
        matcherResults[mIndex] = MatchingState::kNotMatched;
        return;
    }

    bool matched = matchesSimple(mUidMap, mMatcher, event);
    matcherResults[mIndex] = matched ? MatchingState::kMatched : MatchingState::kNotMatched;
    VLOG("Stats SimpleAtomMatcher %lld matched? %d", (long long)mId, matched);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
