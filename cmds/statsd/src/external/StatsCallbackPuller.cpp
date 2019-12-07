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

#include "StatsCallbackPuller.h"

#include <android/os/IPullAtomCallback.h>
#include <android/util/StatsEventParcel.h>

#include "PullResultReceiver.h"
#include "StatsPullerManager.h"
#include "logd/LogEvent.h"
#include "stats_log_util.h"

using namespace android::binder;
using namespace android::util;
using namespace std;

namespace android {
namespace os {
namespace statsd {

StatsCallbackPuller::StatsCallbackPuller(int tagId, const sp<IPullAtomCallback>& callback)
    : StatsPuller(tagId), mCallback(callback) {
    VLOG("StatsCallbackPuller created for tag %d", tagId);
}

bool StatsCallbackPuller::PullInternal(vector<shared_ptr<LogEvent>>* data) {
    VLOG("StatsCallbackPuller called for tag %d", mTagId)
    if(mCallback == nullptr) {
        ALOGW("No callback registered");
        return false;
    }

    // Shared variables needed in the result receiver.
    shared_ptr<mutex> cv_mutex = make_shared<mutex>();
    shared_ptr<condition_variable> cv = make_shared<condition_variable>();
    shared_ptr<bool> pullFinish = make_shared<bool>(false);
    shared_ptr<bool> pullSuccess = make_shared<bool>(false);
    shared_ptr<vector<shared_ptr<LogEvent>>> sharedData =
            make_shared<vector<shared_ptr<LogEvent>>>();

    sp<PullResultReceiver> resultReceiver = new PullResultReceiver(
            [cv_mutex, cv, pullFinish, pullSuccess, sharedData](
                    int32_t atomTag, bool success, const vector<StatsEventParcel>& output) {
                // This is the result of the pull, executing in a statsd binder thread.
                // The pull could have taken a long time, and we should only modify
                // data (the output param) if the pointer is in scope and the pull did not time out.
                {
                    lock_guard<mutex> lk(*cv_mutex);
                    for (const StatsEventParcel& parcel: output) {
                        shared_ptr<LogEvent> event =
                              make_shared<LogEvent>(const_cast<uint8_t*>(parcel.buffer.data()),
                                                    parcel.buffer.size(),
                                                    /*uid=*/ -1);
                        sharedData->push_back(event);
                    }
                    *pullSuccess = success;
                    *pullFinish = true;
                }
                cv->notify_one();
            });

    // Initiate the pull.
    Status status = mCallback->onPullAtom(mTagId, resultReceiver);
    if (!status.isOk()) {
        return false;
    }

    {
        unique_lock<mutex> unique_lk(*cv_mutex);
        int64_t pullTimeoutNs =
                StatsPullerManager::kAllPullAtomInfo.at({.atomTag = mTagId}).pullTimeoutNs;
        // Wait until the pull finishes, or until the pull timeout.
        cv->wait_for(unique_lk, chrono::nanoseconds(pullTimeoutNs),
                     [pullFinish] { return *pullFinish; });
        if (!*pullFinish) {
            // Note: The parent stats puller will also note that there was a timeout and that the
            // cache should be cleared. Once we migrate all pullers to this callback, we could
            // consolidate the logic.
            return true;
        } else {
            // Only copy the data if we did not timeout and the pull was successful.
            if (pullSuccess) {
                *data = std::move(*sharedData);
            }
            VLOG("StatsCallbackPuller::pull succeeded for %d", mTagId);
            return *pullSuccess;
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
