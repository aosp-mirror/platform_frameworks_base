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

#ifndef LOG_MATCHING_TRACKER_H
#define LOG_MATCHING_TRACKER_H

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "logd/LogEvent.h"
#include "matchers/matcher_util.h"

#include <utils/RefBase.h>

#include <set>
#include <unordered_map>
#include <vector>

namespace android {
namespace os {
namespace statsd {

class LogMatchingTracker : public virtual RefBase {
public:
    LogMatchingTracker(const int64_t& id, const int index)
        : mId(id), mIndex(index), mInitialized(false){};

    virtual ~LogMatchingTracker(){};

    // Initialize this LogMatchingTracker.
    // allLogMatchers: the list of the AtomMatcher proto config. This is needed because we don't
    //                 store the proto object in memory. We only need it during initilization.
    // allTrackers: the list of the LogMatchingTracker objects. It's a one-to-one mapping with
    //              allLogMatchers. This is needed because the initialization is done recursively
    //              for CombinationLogMatchingTrackers using DFS.
    // stack: a bit map to record which matcher has been visited on the stack. This is for detecting
    //        circle dependency.
    virtual bool init(const std::vector<AtomMatcher>& allLogMatchers,
                      const std::vector<sp<LogMatchingTracker>>& allTrackers,
                      const std::unordered_map<int64_t, int>& matcherMap,
                      std::vector<bool>& stack) = 0;

    // Called when a log event comes.
    // event: the log event.
    // allTrackers: the list of all LogMatchingTrackers. This is needed because the log processing
    //              is done recursively.
    // matcherResults: The cached results for all matchers for this event. Parent matchers can
    //                 directly access the children's matching results if they have been evaluated.
    //                 Otherwise, call children matchers' onLogEvent.
    virtual void onLogEvent(const LogEvent& event,
                            const std::vector<sp<LogMatchingTracker>>& allTrackers,
                            std::vector<MatchingState>& matcherResults) = 0;

    // Get the tagIds that this matcher cares about. The combined collection is stored
    // in MetricMananger, so that we can pass any LogEvents that are not interest of us. It uses
    // some memory but hopefully it can save us much CPU time when there is flood of events.
    virtual const std::set<int>& getAtomIds() const {
        return mAtomIds;
    }

    const int64_t& getId() const {
        return mId;
    }

protected:
    // Name of this matching. We don't really need the name, but it makes log message easy to debug.
    const int64_t mId;

    // Index of this LogMatchingTracker in MetricsManager's container.
    const int mIndex;

    // Whether this LogMatchingTracker has been properly initialized.
    bool mInitialized;

    // The collection of the event tag ids that this LogMatchingTracker cares. So we can quickly
    // return kNotMatched when we receive an event with an id not in the list. This is especially
    // useful when we have a complex CombinationLogMatcherTracker.
    std::set<int> mAtomIds;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // LOG_MATCHING_TRACKER_H
