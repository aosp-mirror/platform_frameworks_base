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
#include <parse_util.h>
#include <utils/Errors.h>

using namespace android;
using std::make_unique;
using std::unique_ptr;
using std::vector;

namespace android {
namespace os {
namespace statsd {

StatsLogProcessor::StatsLogProcessor() : m_dropbox_writer("all-logs") {
    // hardcoded config
    // this should be called from StatsService when it receives a statsd_config
    UpdateConfig(0, buildFakeConfig());
}

StatsLogProcessor::~StatsLogProcessor() {
}

StatsdConfig StatsLogProcessor::buildFakeConfig() {
    // HACK: Hard code a test metric for counting screen on events...
    StatsdConfig config;
    config.set_config_id(12345L);

    CountMetric* metric = config.add_count_metric();
    metric->set_metric_id(20150717L);
    metric->set_what("SCREEN_IS_ON");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);

    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->add_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()
            ->set_key(1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)
            ->set_eq_int(2/*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);
    return config;
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

    mMetricsManagers.insert({config_source, std::make_unique<MetricsManager>(config)});
}

}  // namespace statsd
}  // namespace os
}  // namespace android
