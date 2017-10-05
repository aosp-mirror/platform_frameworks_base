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

#include <StatsLogProcessor.h>

#include <cutils/log.h>
#include <frameworks/base/cmds/statsd/src/stats_log.pb.h>
#include <log/log_event_list.h>
#include <metrics/CountMetricProducer.h>
#include <utils/Errors.h>

using namespace android;
using std::make_unique;
using std::unique_ptr;
using std::vector;

namespace android {
namespace os {
namespace statsd {

StatsLogProcessor::StatsLogProcessor(const sp<UidMap> &uidMap)
        : m_dropbox_writer("all-logs"), m_UidMap(uidMap)
{
    // hardcoded config
    // this should be called from StatsService when it receives a statsd_config
    UpdateConfig(0, buildFakeConfig());
}

StatsLogProcessor::~StatsLogProcessor() {
}

// TODO: what if statsd service restarts? How do we know what logs are already processed before?
void StatsLogProcessor::OnLogEvent(const log_msg& msg) {
    // TODO: Use EventMetric to filter the events we want to log.
    EventMetricData eventMetricData = parse(msg);
    m_dropbox_writer.addEventMetricData(eventMetricData);

    // pass the event to metrics managers.
    for (auto& pair : mMetricsManagers) {
        pair.second->onLogEvent(msg);
    }
}

void StatsLogProcessor::UpdateConfig(const int config_source, const StatsdConfig& config) {
    auto it = mMetricsManagers.find(config_source);
    if (it != mMetricsManagers.end()) {
        it->second->finish();
    }

    ALOGD("Updated configuration for source %i", config_source);

    unique_ptr<MetricsManager> newMetricsManager = std::make_unique<MetricsManager>(config);
    if (newMetricsManager->isConfigValid()) {
        mMetricsManagers.insert({config_source, std::move(newMetricsManager)});
        ALOGD("StatsdConfig valid");
    } else {
        // If there is any error in the config, don't use it.
        ALOGD("StatsdConfig NOT valid");
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
