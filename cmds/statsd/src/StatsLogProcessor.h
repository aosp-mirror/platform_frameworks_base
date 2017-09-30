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

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "DropboxWriter.h"
#include "LogReader.h"
#include "metrics/MetricsManager.h"
#include "parse_util.h"

#include <log/logprint.h>
#include <stdio.h>
#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

class StatsLogProcessor : public LogListener {
public:
    StatsLogProcessor();
    virtual ~StatsLogProcessor();

    virtual void OnLogEvent(const log_msg& msg);

    void UpdateConfig(const int config_source, const StatsdConfig& config);

private:
    // TODO: use EventMetrics to log the events.
    DropboxWriter m_dropbox_writer;

    std::unordered_map<int, std::unique_ptr<MetricsManager>> mMetricsManagers;

    static StatsdConfig buildFakeConfig();
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATS_LOG_PROCESSOR_H
