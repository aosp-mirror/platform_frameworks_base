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

#include "Log.h"

#include <android/os/IStatsCompanionService.h>
#include <binder/IPCThreadState.h>
#include <private/android_filesystem_config.h>
#include "StatsService.h"
#include "external/KernelWakelockPuller.h"
#include "external/StatsPuller.h"

using namespace android;
using namespace android::base;
using namespace android::binder;
using namespace android::os;
using namespace std;

namespace android {
namespace os {
namespace statsd {

const int KernelWakelockPuller::PULL_CODE_KERNEL_WAKELOCKS = 20;

// The reading and parsing are implemented in Java. It is not difficult to port over. But for now
// let StatsCompanionService handle that and send the data back.
String16 KernelWakelockPuller::pull() {
    sp<IStatsCompanionService> statsCompanion = StatsService::getStatsCompanionService();
    String16 returned_value("");
    if (statsCompanion != NULL) {
        Status status = statsCompanion->pullData(KernelWakelockPuller::PULL_CODE_KERNEL_WAKELOCKS,
                                                 &returned_value);
        if (!status.isOk()) {
            ALOGW("error pulling kernel wakelock");
        }
        ALOGD("KernelWakelockPuller::pull succeeded!");
        // TODO: remove this when we integrate into aggregation chain.
        ALOGD("%s", String8(returned_value).string());
        return returned_value;
    } else {
        ALOGW("statsCompanion not found!");
        return String16();
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
