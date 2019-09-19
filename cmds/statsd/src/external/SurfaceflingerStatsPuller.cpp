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

#include "SurfaceflingerStatsPuller.h"

#include <cutils/compiler.h>

#include <numeric>

#include "logd/LogEvent.h"
#include "stats_log_util.h"
#include "statslog.h"

namespace android {
namespace os {
namespace statsd {

SurfaceflingerStatsPuller::SurfaceflingerStatsPuller(const int tagId) : StatsPuller(tagId) {
}

bool SurfaceflingerStatsPuller::PullInternal(std::vector<std::shared_ptr<LogEvent>>* data) {
    switch (mTagId) {
        case android::util::SURFACEFLINGER_STATS_GLOBAL_INFO:
            return pullGlobalInfo(data);
        default:
            break;
    }

    return false;
}

static int64_t getTotalTime(
        const google::protobuf::RepeatedPtrField<surfaceflinger::SFTimeStatsHistogramBucketProto>&
                buckets) {
    int64_t total = 0;
    for (const auto& bucket : buckets) {
        if (bucket.time_millis() == 1000) {
            continue;
        }

        total += bucket.time_millis() * bucket.frame_count();
    }

    return total;
}

bool SurfaceflingerStatsPuller::pullGlobalInfo(std::vector<std::shared_ptr<LogEvent>>* data) {
    std::string protoBytes;
    if (CC_UNLIKELY(mStatsProvider)) {
        protoBytes = mStatsProvider();
    } else {
        std::unique_ptr<FILE, decltype(&pclose)> pipe(popen("dumpsys SurfaceFlinger --timestats -dump --proto", "r"), pclose);
        if (!pipe.get()) {
            return false;
        }
        char buf[1024];
        size_t bytesRead = 0;
        do {
            bytesRead = fread(buf, 1, sizeof(buf), pipe.get());
            protoBytes.append(buf, bytesRead);
        } while (bytesRead > 0);
    }
    surfaceflinger::SFTimeStatsGlobalProto proto;
    proto.ParseFromString(protoBytes);

    int64_t totalTime = getTotalTime(proto.present_to_present());

    data->clear();
    data->reserve(1);
    std::shared_ptr<LogEvent> event =
            make_shared<LogEvent>(android::util::SURFACEFLINGER_STATS_GLOBAL_INFO, getWallClockNs(),
                                  getElapsedRealtimeNs());
    if (!event->write(proto.total_frames())) return false;
    if (!event->write(proto.missed_frames())) return false;
    if (!event->write(proto.client_composition_frames())) return false;
    if (!event->write(proto.display_on_time())) return false;
    if (!event->write(totalTime)) return false;
    event->init();
    data->emplace_back(event);

    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
