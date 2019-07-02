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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include <android/os/IStatsPullerCallback.h>

#include "StatsCallbackPuller.h"
#include "logd/LogEvent.h"
#include "stats_log_util.h"

using namespace android::binder;

namespace android {
namespace os {
namespace statsd {

StatsCallbackPuller::StatsCallbackPuller(int tagId, const sp<IStatsPullerCallback>& callback) :
        StatsPuller(tagId), mCallback(callback) {
        VLOG("StatsCallbackPuller created for tag %d", tagId);
}

bool StatsCallbackPuller::PullInternal(vector<shared_ptr<LogEvent>>* data) {
    VLOG("StatsCallbackPuller called for tag %d", mTagId)
    if(mCallback == nullptr) {
        ALOGW("No callback registered");
        return false;
    }
    int64_t wallClockTimeNs = getWallClockNs();
    int64_t elapsedTimeNs = getElapsedRealtimeNs();
    vector<StatsLogEventWrapper> returned_value;
    Status status = mCallback->pullData(mTagId, elapsedTimeNs, wallClockTimeNs, &returned_value);
    if (!status.isOk()) {
        ALOGW("StatsCallbackPuller::pull failed for %d", mTagId);
        return false;
    }
    data->clear();
    for (const StatsLogEventWrapper& it: returned_value) {
        LogEvent::createLogEvents(it, *data);
    }
    VLOG("StatsCallbackPuller::pull succeeded for %d", mTagId);
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
