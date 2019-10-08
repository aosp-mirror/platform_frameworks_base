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

#include <android/hardware/health/2.0/IHealth.h>
#include <healthhalutils/HealthHalUtils.h>
#include "external/ResourceHealthManagerPuller.h"
#include "external/StatsPuller.h"

#include "ResourceHealthManagerPuller.h"
#include "logd/LogEvent.h"
#include "stats_log_util.h"
#include "statslog.h"

using android::hardware::hidl_vec;
using android::hardware::Return;
using android::hardware::Void;
using android::hardware::health::V2_0::get_health_service;
using android::hardware::health::V2_0::HealthInfo;
using android::hardware::health::V2_0::IHealth;
using android::hardware::health::V2_0::Result;

using std::make_shared;
using std::shared_ptr;

namespace android {
namespace os {
namespace statsd {

sp<android::hardware::health::V2_0::IHealth> gHealthHal = nullptr;

bool getHealthHal() {
    if (gHealthHal == nullptr) {
        gHealthHal = get_health_service();
    }
    return gHealthHal != nullptr;
}

ResourceHealthManagerPuller::ResourceHealthManagerPuller(int tagId) : StatsPuller(tagId) {
}

// TODO(b/110565992): add other health atoms (eg. Temperature).
bool ResourceHealthManagerPuller::PullInternal(vector<shared_ptr<LogEvent>>* data) {
    if (!getHealthHal()) {
        ALOGE("Health Hal not loaded");
        return false;
    }

    int64_t wallClockTimestampNs = getWallClockNs();
    int64_t elapsedTimestampNs = getElapsedRealtimeNs();

    data->clear();
    bool result_success = true;

    // Get the data from the Health HAL (hardware/interfaces/health/1.0/types.hal).
    Return<void> ret = gHealthHal->getHealthInfo([&](Result r, HealthInfo v) {
        if (r != Result::SUCCESS) {
            result_success = false;
            return;
        }
        if (mTagId == android::util::REMAINING_BATTERY_CAPACITY) {
            auto ptr = make_shared<LogEvent>(android::util::REMAINING_BATTERY_CAPACITY,
                                             wallClockTimestampNs, elapsedTimestampNs);
            ptr->write(v.legacy.batteryChargeCounter);
            ptr->init();
            data->push_back(ptr);
        } else if (mTagId == android::util::FULL_BATTERY_CAPACITY) {
            auto ptr = make_shared<LogEvent>(android::util::FULL_BATTERY_CAPACITY,
                                             wallClockTimestampNs, elapsedTimestampNs);
            ptr->write(v.legacy.batteryFullCharge);
            ptr->init();
            data->push_back(ptr);
        } else if (mTagId == android::util::BATTERY_VOLTAGE) {
            auto ptr = make_shared<LogEvent>(android::util::BATTERY_VOLTAGE, wallClockTimestampNs,
                                             elapsedTimestampNs);
            ptr->write(v.legacy.batteryVoltage);
            ptr->init();
            data->push_back(ptr);
        } else if (mTagId == android::util::BATTERY_LEVEL) {
            auto ptr = make_shared<LogEvent>(android::util::BATTERY_LEVEL, wallClockTimestampNs,
                                             elapsedTimestampNs);
            ptr->write(v.legacy.batteryLevel);
            ptr->init();
            data->push_back(ptr);
        } else if (mTagId == android::util::BATTERY_CYCLE_COUNT) {
            auto ptr = make_shared<LogEvent>(android::util::BATTERY_CYCLE_COUNT,
                                             wallClockTimestampNs, elapsedTimestampNs);
            ptr->write(v.legacy.batteryCycleCount);
            ptr->init();
            data->push_back(ptr);
        } else {
            ALOGE("Unsupported tag in ResourceHealthManagerPuller: %d", mTagId);
        }
    });
    if (!result_success || !ret.isOk()) {
        ALOGE("getHealthHal() failed: health HAL service not available. Description: %s",
              ret.description().c_str());
        if (!ret.isOk() && ret.isDeadObject()) {
            gHealthHal = nullptr;
        }
        return false;
    }
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
