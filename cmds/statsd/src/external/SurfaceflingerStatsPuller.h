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

#pragma once

#include <timestatsproto/TimeStatsProtoHeader.h>

#include "StatsPuller.h"

namespace android {
namespace os {
namespace statsd {

/**
 * Pull metrics from Surfaceflinger
 */
class SurfaceflingerStatsPuller : public StatsPuller {
public:
    explicit SurfaceflingerStatsPuller(const int tagId);

    // StatsPuller interface
    bool PullInternal(std::vector<std::shared_ptr<LogEvent>>* data) override;

protected:
    // Test-only, for injecting fake data
    using StatsProvider = std::function<std::string()>;
    StatsProvider mStatsProvider;

private:
    bool pullGlobalInfo(std::vector<std::shared_ptr<LogEvent>>* data);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
