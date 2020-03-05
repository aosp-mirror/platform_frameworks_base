/*
 * Copyright 2019 The Android Open Source Project
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

#include <binder/IServiceManager.h>
#include <com/android/internal/car/ICarStatsService.h>

#include "CarStatsPuller.h"
#include "logd/LogEvent.h"
#include "stats_log_util.h"

using android::binder::Status;
using com::android::internal::car::ICarStatsService;

namespace android {
namespace os {
namespace statsd {

static std::mutex gCarStatsMutex;
static sp<ICarStatsService> gCarStats = nullptr;

class CarStatsDeathRecipient : public android::IBinder::DeathRecipient {
 public:
     CarStatsDeathRecipient() = default;
     ~CarStatsDeathRecipient() override = default;

  // android::IBinder::DeathRecipient override:
  void binderDied(const android::wp<android::IBinder>& /* who */) override {
      ALOGE("Car service has died");
      std::lock_guard<std::mutex> lock(gCarStatsMutex);
      if (gCarStats) {
          sp<IBinder> binder = IInterface::asBinder(gCarStats);
          binder->unlinkToDeath(this);
          gCarStats = nullptr;
      }
  }
};

static sp<CarStatsDeathRecipient> gDeathRecipient = new CarStatsDeathRecipient();

static sp<ICarStatsService> getCarService() {
    std::lock_guard<std::mutex> lock(gCarStatsMutex);
    if (!gCarStats) {
        const sp<IBinder> binder = defaultServiceManager()->checkService(String16("car_stats"));
        if (!binder) {
            ALOGW("Car service is unavailable");
            return nullptr;
        }
        gCarStats = interface_cast<ICarStatsService>(binder);
        binder->linkToDeath(gDeathRecipient);
    }
    return gCarStats;
}

CarStatsPuller::CarStatsPuller(const int tagId) : StatsPuller(tagId) {
}

bool CarStatsPuller::PullInternal(std::vector<std::shared_ptr<LogEvent>>* data) {
    const sp<ICarStatsService> carService = getCarService();
    if (!carService) {
        return false;
    }

    vector<StatsLogEventWrapper> returned_value;
    Status status = carService->pullData(mTagId, &returned_value);
    if (!status.isOk()) {
        ALOGW("CarStatsPuller::pull failed for %d", mTagId);
        return false;
    }

    data->clear();
    for (const StatsLogEventWrapper& it : returned_value) {
        LogEvent::createLogEvents(it, *data);
    }
    VLOG("CarStatsPuller::pull succeeded for %d", mTagId);
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
