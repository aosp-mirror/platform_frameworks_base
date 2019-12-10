/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include <android/os/IPullAtomCallback.h>
#include <utils/String16.h>

#include "StatsPuller.h"

namespace android {
namespace os {
namespace statsd {

class StatsCallbackPuller : public StatsPuller {
public:
    explicit StatsCallbackPuller(int tagId, const sp<IPullAtomCallback>& callback,
                                 int64_t timeoutNs);

private:
    bool PullInternal(vector<std::shared_ptr<LogEvent> >* data) override;
    const sp<IPullAtomCallback> mCallback;
    const int64_t mTimeoutNs;

    FRIEND_TEST(StatsCallbackPullerTest, PullFail);
    FRIEND_TEST(StatsCallbackPullerTest, PullSuccess);
    FRIEND_TEST(StatsCallbackPullerTest, PullTimeout);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
