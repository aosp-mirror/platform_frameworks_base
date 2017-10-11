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

#ifndef CONDITION_TRACKER_H
#define CONDITION_TRACKER_H

#include <cutils/log.h>
#include <log/logprint.h>
#include <utils/RefBase.h>
#include <unordered_map>
#include "../matchers/LogMatchingTracker.h"
#include "../matchers/matcher_util.h"
#include "condition_util.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

namespace android {
namespace os {
namespace statsd {

class ConditionTracker : public virtual RefBase {
public:
    ConditionTracker(const std::string& name, const int index)
        : mName(name),
          mIndex(index),
          mInitialized(false),
          mConditionState(ConditionState::kUnknown),
          mTrackerIndex(){};

    virtual ~ConditionTracker(){};

    // Initialize this ConditionTracker. This initialization is done recursively (DFS). It can also
    // be done in the constructor, but we do it separately because (1) easy to return a bool to
    // indicate whether the initialization is successful. (2) makes unit test easier.
    // allConditionConfig: the list of all Condition config from statsd_config.
    // allConditionTrackers: the list of all ConditionTrackers (this is needed because we may also
    //                       need to call init() on children conditions)
    // conditionNameIndexMap: the mapping from condition name to its index.
    // stack: a bit map to keep track which nodes have been visited on the stack in the recursion.
    virtual bool init(const std::vector<Condition>& allConditionConfig,
                      const std::vector<sp<ConditionTracker>>& allConditionTrackers,
                      const std::unordered_map<std::string, int>& conditionNameIndexMap,
                      std::vector<bool>& stack) = 0;

    // evaluate current condition given the new event.
    // return true if the condition state changed, false if the condition state is not changed.
    // event: the new log event
    // eventMatcherValues: the results of the LogMatcherTrackers. LogMatcherTrackers always process
    //                     event before ConditionTrackers, because ConditionTracker depends on
    //                     LogMatchingTrackers.
    // mAllConditions: the list of all ConditionTracker
    // conditionCache: the cached results of the ConditionTrackers for this new event.
    // changedCache: the bit map to record whether the condition has changed.
    virtual bool evaluateCondition(const LogEventWrapper& event,
                                   const std::vector<MatchingState>& eventMatcherValues,
                                   const std::vector<sp<ConditionTracker>>& mAllConditions,
                                   std::vector<ConditionState>& conditionCache,
                                   std::vector<bool>& changedCache) = 0;

    // Return the current condition state.
    virtual ConditionState isConditionMet() {
        ALOGW("Condition %s value %d", mName.c_str(), mConditionState);
        return mConditionState;
    };

    // return the list of LogMatchingTracker index that this ConditionTracker uses.
    virtual const std::set<int>& getLogTrackerIndex() const {
        return mTrackerIndex;
    }

protected:
    // We don't really need the string name, but having a name here makes log messages
    // easy to debug.
    const std::string mName;

    // the index of this condition in the manager's condition list.
    const int mIndex;

    // if it's properly initialized.
    bool mInitialized;

    // current condition state.
    ConditionState mConditionState;

    // the list of LogMatchingTracker index that this ConditionTracker uses.
    std::set<int> mTrackerIndex;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // CONDITION_TRACKER_H
