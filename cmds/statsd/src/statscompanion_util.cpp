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

#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "statscompanion_util.h"

namespace android {
namespace os {
namespace statsd {

sp <IStatsCompanionService> getStatsCompanionService() {
    sp<IStatsCompanionService> statsCompanion = nullptr;
    // Get statscompanion service from service manager
    static const sp <IServiceManager> sm(defaultServiceManager());
    if (statsCompanion == nullptr) {
        if (sm != nullptr) {
            const String16 name("statscompanion");
            statsCompanion = interface_cast<IStatsCompanionService>(sm->checkService(name));
            if (statsCompanion == nullptr) {
                ALOGW("statscompanion service unavailable!");
                return nullptr;
            }
        }
    }
    return statsCompanion;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
