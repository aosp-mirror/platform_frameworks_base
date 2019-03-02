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

#include "GpuStatsPuller.h"

#include <binder/IServiceManager.h>
#include <graphicsenv/GpuStatsInfo.h>
#include <graphicsenv/IGpuService.h>

#include "logd/LogEvent.h"

#include "stats_log_util.h"
#include "statslog.h"

namespace android {
namespace os {
namespace statsd {

GpuStatsPuller::GpuStatsPuller(const int tagId) : StatsPuller(tagId) {
}

static sp<IGpuService> getGpuService() {
    const sp<IBinder> binder = defaultServiceManager()->checkService(String16("gpu"));
    if (!binder) {
        ALOGE("Failed to get gpu service");
        return nullptr;
    }

    return interface_cast<IGpuService>(binder);
}

static bool pullGpuStatsGlobalInfo(const sp<IGpuService>& gpuService,
                                   std::vector<std::shared_ptr<LogEvent>>* data) {
    std::vector<GpuStatsGlobalInfo> stats;
    status_t status = gpuService->getGpuStatsGlobalInfo(&stats);
    if (status != OK) {
        return false;
    }

    data->clear();
    data->reserve(stats.size());
    for (const auto& info : stats) {
        std::shared_ptr<LogEvent> event = make_shared<LogEvent>(
                android::util::GPU_STATS_GLOBAL_INFO, getWallClockNs(), getElapsedRealtimeNs());
        if (!event->write(info.driverPackageName)) return false;
        if (!event->write(info.driverVersionName)) return false;
        if (!event->write((int64_t)info.driverVersionCode)) return false;
        if (!event->write(info.driverBuildTime)) return false;
        if (!event->write((int64_t)info.glLoadingCount)) return false;
        if (!event->write((int64_t)info.glLoadingFailureCount)) return false;
        if (!event->write((int64_t)info.vkLoadingCount)) return false;
        if (!event->write((int64_t)info.vkLoadingFailureCount)) return false;
        event->init();
        data->emplace_back(event);
    }

    return true;
}

bool GpuStatsPuller::PullInternal(std::vector<std::shared_ptr<LogEvent>>* data) {
    const sp<IGpuService> gpuService = getGpuService();
    if (!gpuService) {
        return false;
    }

    switch (mTagId) {
        case android::util::GPU_STATS_GLOBAL_INFO:
            return pullGpuStatsGlobalInfo(gpuService, data);
        default:
            break;
    }

    return false;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
