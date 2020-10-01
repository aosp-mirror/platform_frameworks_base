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

#include "external/StatsPuller.h"

#include "TrainInfoPuller.h"
#include "logd/LogEvent.h"
#include "stats_log_util.h"
#include "statslog_statsd.h"
#include "storage/StorageManager.h"

using std::make_shared;
using std::shared_ptr;

namespace android {
namespace os {
namespace statsd {

TrainInfoPuller::TrainInfoPuller() :
    StatsPuller(util::TRAIN_INFO) {
}

bool TrainInfoPuller::PullInternal(vector<shared_ptr<LogEvent>>* data) {
    vector<InstallTrainInfo> trainInfoList =
        StorageManager::readAllTrainInfo();
    if (trainInfoList.empty()) {
        ALOGW("Train info was empty.");
        return true;
    }
    for (InstallTrainInfo& trainInfo : trainInfoList) {
        auto event = make_shared<LogEvent>(getWallClockNs(), getElapsedRealtimeNs(), trainInfo);
        data->push_back(event);
    }
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
