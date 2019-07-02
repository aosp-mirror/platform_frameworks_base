/*
 * Copyright (C) 2018 The Android Open Source Project
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

#pragma once

#include "LogMatchingTracker.h"

namespace android {
namespace os {
namespace statsd {

class EventMatcherWizard : public virtual android::RefBase {
public:
    EventMatcherWizard(){};  // for testing
    EventMatcherWizard(const std::vector<sp<LogMatchingTracker>>& eventTrackers)
        : mAllEventMatchers(eventTrackers){};

    virtual ~EventMatcherWizard(){};

    MatchingState matchLogEvent(const LogEvent& event, int matcher_index);

private:
    std::vector<sp<LogMatchingTracker>> mAllEventMatchers;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
