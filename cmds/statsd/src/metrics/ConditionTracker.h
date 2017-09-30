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

#include <utils/RefBase.h>
#include "../matchers/LogEntryMatcherManager.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

namespace android {
namespace os {
namespace statsd {

class ConditionTracker : public RefBase {
public:
    ConditionTracker();

    ConditionTracker(const Condition& condition);

    ~ConditionTracker();

    void evaluateCondition(const LogEventWrapper& event);

    bool isConditionMet() const;

private:
    // this is the definition of the Condition.
    Condition mCondition;

    bool mIsConditionMet;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // CONDITION_TRACKER_H
