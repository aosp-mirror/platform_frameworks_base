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

#define DEBUG false
#include "Log.h"

#include <android/os/IStatsCompanionService.h>
#include <binder/IPCThreadState.h>
#include <private/android_filesystem_config.h>
#include "../stats_log_util.h"
#include "../statscompanion_util.h"
#include "StatsCompanionServicePuller.h"

using namespace android;
using namespace android::base;
using namespace android::binder;
using namespace android::os;
using std::make_shared;
using std::shared_ptr;
using std::vector;

namespace android {
namespace os {
namespace statsd {

// The reading and parsing are implemented in Java. It is not difficult to port over. But for now
// let StatsCompanionService handle that and send the data back.
StatsCompanionServicePuller::StatsCompanionServicePuller(int tagId) : StatsPuller(tagId) {
}

void StatsCompanionServicePuller::SetStatsCompanionService(
        sp<IStatsCompanionService> statsCompanionService) {
    AutoMutex _l(mStatsCompanionServiceLock);
    sp<IStatsCompanionService> tmpForLock = mStatsCompanionService;
    mStatsCompanionService = statsCompanionService;
}

bool StatsCompanionServicePuller::PullInternal(vector<shared_ptr<LogEvent> >* data) {
    sp<IStatsCompanionService> statsCompanionServiceCopy = mStatsCompanionService;
    if (statsCompanionServiceCopy != nullptr) {
        vector<StatsLogEventWrapper> returned_value;
        Status status = statsCompanionServiceCopy->pullData(mTagId, &returned_value);
        if (!status.isOk()) {
            ALOGW("StatsCompanionServicePuller::pull failed for %d", mTagId);
            StatsdStats::getInstance().noteStatsCompanionPullFailed(mTagId);
            if (status.exceptionCode() == Status::Exception::EX_TRANSACTION_FAILED) {
                StatsdStats::getInstance().noteStatsCompanionPullBinderTransactionFailed(mTagId);
            }
            return false;
        }
        data->clear();
        for (const StatsLogEventWrapper& it : returned_value) {
            LogEvent::createLogEvents(it, *data);
        }
        VLOG("StatsCompanionServicePuller::pull succeeded for %d", mTagId);
        return true;
    } else {
        ALOGW("statsCompanion not found!");
        return false;
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
