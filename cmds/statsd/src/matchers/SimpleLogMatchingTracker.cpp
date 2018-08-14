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

#include "SimpleLogMatchingTracker.h"

namespace android {
namespace os {
namespace statsd {

using std::string;
using std::unique_ptr;
using std::unordered_map;
using std::vector;


SimpleLogMatchingTracker::SimpleLogMatchingTracker(const int64_t& id, const int index,
                                                   const SimpleAtomMatcher& matcher,
                                                   const UidMap& uidMap)
    : LogMatchingTracker(id, index), mMatcher(matcher), mUidMap(uidMap) {
    if (!matcher.has_atom_id()) {
        mInitialized = false;
    } else {
        mAtomIds.insert(matcher.atom_id());
        mInitialized = true;
    }
}

SimpleLogMatchingTracker::~SimpleLogMatchingTracker() {
}

bool SimpleLogMatchingTracker::init(const vector<AtomMatcher>& allLogMatchers,
                                    const vector<sp<LogMatchingTracker>>& allTrackers,
                                    const unordered_map<int64_t, int>& matcherMap,
                                    vector<bool>& stack) {
    // no need to do anything.
    return mInitialized;
}

void SimpleLogMatchingTracker::onLogEvent(const LogEvent& event,
                                          const vector<sp<LogMatchingTracker>>& allTrackers,
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
    VLOG("Stats SimpleLogMatcher %lld matched? %d", (long long)mId, matched);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
