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

#define LOG_TAG "ConditionTracker"
#define DEBUG true  // STOPSHIP if true
#define VLOG(...) \
    if (DEBUG) ALOGD(__VA_ARGS__);

#include "ConditionTracker.h"
#include <cutils/log.h>

namespace android {
namespace os {
namespace statsd {

ConditionTracker::ConditionTracker() : mIsConditionMet(true) {
    VLOG("ConditionTracker()");
}

ConditionTracker::ConditionTracker(const Condition& condition)
    : mCondition(condition), mIsConditionMet(true) {
    VLOG("ConditionTracker()");
}

ConditionTracker::~ConditionTracker() {
    VLOG("~ConditionTracker()");
}

void ConditionTracker::evaluateCondition(const LogEventWrapper& event) {
    // modify condition.
    VLOG("evaluateCondition");
}

bool ConditionTracker::isConditionMet() const {
    VLOG("isConditionMet() %d", mIsConditionMet);
    return mIsConditionMet;
}

}  // namespace statsd
}  // namespace os
}  // namespace android