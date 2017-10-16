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

#include "SimpleLogMatchingTracker.h"

#include <log/logprint.h>

namespace android {
namespace os {
namespace statsd {

using std::string;
using std::unique_ptr;
using std::unordered_map;
using std::vector;


SimpleLogMatchingTracker::SimpleLogMatchingTracker(const string& name, const int index,
                                                   const SimpleLogEntryMatcher& matcher)
    : LogMatchingTracker(name, index), mMatcher(matcher) {
    for (int i = 0; i < matcher.tag_size(); i++) {
        mTagIds.insert(matcher.tag(i));
    }
    mInitialized = true;
}

SimpleLogMatchingTracker::~SimpleLogMatchingTracker() {
}

bool SimpleLogMatchingTracker::init(const vector<LogEntryMatcher>& allLogMatchers,
                                    const vector<sp<LogMatchingTracker>>& allTrackers,
                                    const unordered_map<string, int>& matcherMap,
                                    vector<bool>& stack) {
    // no need to do anything.
    return true;
}

void SimpleLogMatchingTracker::onLogEvent(const LogEventWrapper& event,
                                          const vector<sp<LogMatchingTracker>>& allTrackers,
                                          vector<MatchingState>& matcherResults) {
    if (matcherResults[mIndex] != MatchingState::kNotComputed) {
        VLOG("Matcher %s already evaluated ", mName.c_str());
        return;
    }

    if (mTagIds.find(event.tagId) == mTagIds.end()) {
        matcherResults[mIndex] = MatchingState::kNotMatched;
        return;
    }

    bool matched = matchesSimple(mMatcher, event);
    matcherResults[mIndex] = matched ? MatchingState::kMatched : MatchingState::kNotMatched;
    VLOG("Stats SimpleLogMatcher %s matched? %d", mName.c_str(), matched);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
