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

#include <android/hardware/thermal/1.0/IThermal.h>
#include "external/ResourceThermalManagerPuller.h"
#include "external/StatsPuller.h"

#include "ResourceThermalManagerPuller.h"
#include "logd/LogEvent.h"
#include "statslog.h"
#include "stats_log_util.h"

#include <chrono>

using android::hardware::hidl_death_recipient;
using android::hardware::hidl_vec;
using android::hidl::base::V1_0::IBase;
using android::hardware::thermal::V1_0::IThermal;
using android::hardware::thermal::V1_0::Temperature;
using android::hardware::thermal::V1_0::ThermalStatus;
using android::hardware::thermal::V1_0::ThermalStatusCode;
using android::hardware::Return;
using android::hardware::Void;

using std::chrono::duration_cast;
using std::chrono::nanoseconds;
using std::chrono::system_clock;
using std::make_shared;
using std::shared_ptr;

namespace android {
namespace os {
namespace statsd {

bool getThermalHalLocked();
sp<android::hardware::thermal::V1_0::IThermal> gThermalHal = nullptr;
std::mutex gThermalHalMutex;

struct ThermalHalDeathRecipient : virtual public hidl_death_recipient {
      virtual void serviceDied(uint64_t cookie, const wp<IBase>& who) override {
          std::lock_guard<std::mutex> lock(gThermalHalMutex);
          ALOGE("ThermalHAL just died");
          gThermalHal = nullptr;
          getThermalHalLocked();
      }
};

sp<ThermalHalDeathRecipient> gThermalHalDeathRecipient = nullptr;

// The caller must be holding gThermalHalMutex.
bool getThermalHalLocked() {
    if (gThermalHal == nullptr) {
            gThermalHal = IThermal::getService();
            if (gThermalHal == nullptr) {
                ALOGE("Unable to get Thermal service.");
            } else {
                if (gThermalHalDeathRecipient == nullptr) {
                    gThermalHalDeathRecipient = new ThermalHalDeathRecipient();
                }
                hardware::Return<bool> linked = gThermalHal->linkToDeath(
                    gThermalHalDeathRecipient, 0x451F /* cookie */);
                if (!linked.isOk()) {
                    ALOGE("Transaction error in linking to ThermalHAL death: %s",
                            linked.description().c_str());
                    gThermalHal = nullptr;
                } else if (!linked) {
                    ALOGW("Unable to link to ThermalHal death notifications");
                    gThermalHal = nullptr;
                } else {
                    ALOGD("Link to death notification successful");
                }
            }
    }
    return gThermalHal != nullptr;
}

ResourceThermalManagerPuller::ResourceThermalManagerPuller() :
        StatsPuller(android::util::TEMPERATURE) {
}

bool ResourceThermalManagerPuller::PullInternal(vector<shared_ptr<LogEvent>>* data) {
    std::lock_guard<std::mutex> lock(gThermalHalMutex);
    if (!getThermalHalLocked()) {
        ALOGE("Thermal Hal not loaded");
        return false;
    }

    int64_t wallClockTimestampNs = getWallClockNs();
    int64_t elapsedTimestampNs = getElapsedRealtimeNs();

    data->clear();
    bool resultSuccess = true;

    Return<void> ret = gThermalHal->getTemperatures(
            [&](ThermalStatus status, const hidl_vec<Temperature>& temps) {
        if (status.code != ThermalStatusCode::SUCCESS) {
            ALOGE("Failed to get temperatures from ThermalHAL. Status: %d", status.code);
            resultSuccess = false;
            return;
        }
        if (mTagId == android::util::TEMPERATURE) {
            for (size_t i = 0; i < temps.size(); ++i) {
                auto ptr = make_shared<LogEvent>(android::util::TEMPERATURE,
                        wallClockTimestampNs, elapsedTimestampNs);
                ptr->write((static_cast<int>(temps[i].type)));
                ptr->write(temps[i].name);
                // Convert the temperature to an int.
                int32_t temp = static_cast<int>(temps[i].currentValue * 10);
                ptr->write(temp);
                ptr->init();
                data->push_back(ptr);
            }
        } else {
            ALOGE("Unsupported tag in ResourceThermalManagerPuller: %d", mTagId);
        }
    });
    if (!ret.isOk()) {
        ALOGE("getThermalHalLocked() failed: thermal HAL service not available. Description: %s",
                ret.description().c_str());
        if (ret.isDeadObject()) {
            gThermalHal = nullptr;
        }
        return false;
    }
    return resultSuccess;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
