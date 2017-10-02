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

#define LOG_TAG "StatsPuller"
#define DEBUG true

#include "StatsPuller.h"
#include "StatsService.h"
#include <android/os/IStatsCompanionService.h>
#include <cutils/log.h>

using namespace android;

namespace android {
namespace os {
namespace statsd {

String16 StatsPuller::pull(int pullCode) {
    if (DEBUG) ALOGD("Initiating pulling %d", pullCode);

    switch (pullCode) {
        // All stats_companion_service cases go here with fallthroughs
        case PULL_CODE_KERNEL_WAKELOCKS: {
            // TODO: Consider caching the statsCompanion service
            sp <IStatsCompanionService>
                    statsCompanion = StatsService::getStatsCompanionService();
            String16 returned_value("");
            Status status = statsCompanion->pullData(pullCode, &returned_value);
            if (DEBUG) ALOGD("Finished pulling the data");
            if (!status.isOk()) {
                ALOGW("error pulling data of type %d", pullCode);
            }
            return returned_value;
        }

        // case OTHER_TYPES: etc.

        default: {
            ALOGE("invalid pull code %d", pullCode);
            return String16("");
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
