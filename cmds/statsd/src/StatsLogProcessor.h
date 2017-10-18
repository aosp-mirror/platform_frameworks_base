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
#ifndef STATS_LOG_PROCESSOR_H
#define STATS_LOG_PROCESSOR_H

#include "config/ConfigListener.h"
#include "logd/LogReader.h"
#include "metrics/MetricsManager.h"
#include "packages/UidMap.h"
#include "storage/DropboxWriter.h"

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

#include <stdio.h>
#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

class StatsLogProcessor : public ConfigListener {
public:
    StatsLogProcessor(const sp<UidMap> &uidMap);
    virtual ~StatsLogProcessor();

    virtual void OnLogEvent(const LogEvent& event);

    void OnConfigUpdated(const ConfigKey& key, const StatsdConfig& config);
    void OnConfigRemoved(const ConfigKey& key);

private:
    // TODO: use EventMetrics to log the events.
    DropboxWriter m_dropbox_writer;

    std::unordered_map<ConfigKey, std::unique_ptr<MetricsManager>> mMetricsManagers;

    sp<UidMap> mUidMap;  // Reference to the UidMap to lookup app name and version for each uid.
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATS_LOG_PROCESSOR_H
