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

#include "Log.h"

#include "StatsLogProcessor.h"
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "metrics/CountMetricProducer.h"
#include "stats_util.h"

#include <log/log_event_list.h>
#include <utils/Errors.h>

using namespace android;
using std::make_unique;
using std::unique_ptr;
using std::vector;

namespace android {
namespace os {
namespace statsd {

StatsLogProcessor::StatsLogProcessor(const sp<UidMap>& uidMap,
                                     const std::function<void(const vector<uint8_t>&)>& pushLog)
    : mUidMap(uidMap), mPushLog(pushLog) {
}

StatsLogProcessor::~StatsLogProcessor() {
}

// TODO: what if statsd service restarts? How do we know what logs are already processed before?
void StatsLogProcessor::OnLogEvent(const LogEvent& msg) {
    // pass the event to metrics managers.
    for (auto& pair : mMetricsManagers) {
        pair.second->onLogEvent(msg);
    }
}

void StatsLogProcessor::OnConfigUpdated(const ConfigKey& key, const StatsdConfig& config) {
    auto it = mMetricsManagers.find(key);
    if (it != mMetricsManagers.end()) {
        it->second->finish();
    }

    ALOGD("Updated configuration for key %s", key.ToString().c_str());

    unique_ptr<MetricsManager> newMetricsManager = std::make_unique<MetricsManager>(config);
    if (newMetricsManager->isConfigValid()) {
        mMetricsManagers[key] = std::move(newMetricsManager);
        // Why doesn't this work? mMetricsManagers.insert({key, std::move(newMetricsManager)});
        ALOGD("StatsdConfig valid");
    } else {
        // If there is any error in the config, don't use it.
        ALOGD("StatsdConfig NOT valid");
    }
}

vector<StatsLogReport> StatsLogProcessor::onDumpReport(const ConfigKey& key) {
    auto it = mMetricsManagers.find(key);
    if (it == mMetricsManagers.end()) {
        ALOGW("Config source %s does not exist", key.ToString().c_str());
        return vector<StatsLogReport>();
    }

    return it->second->onDumpReport();
}

void StatsLogProcessor::OnConfigRemoved(const ConfigKey& key) {
    auto it = mMetricsManagers.find(key);
    if (it != mMetricsManagers.end()) {
        it->second->finish();
        mMetricsManagers.erase(it);
    }
}

void StatsLogProcessor::addEventMetricData(const EventMetricData& eventMetricData) {
    // TODO: Replace this code when MetricsManager.onDumpReport() is ready to
    // get a list of byte arrays.
    flushIfNecessary(eventMetricData);
    const int numBytes = eventMetricData.ByteSize();
    char buffer[numBytes];
    eventMetricData.SerializeToArray(&buffer[0], numBytes);
    string bufferString(buffer, numBytes);
    mEvents.push_back(bufferString);
    mBufferSize += eventMetricData.ByteSize();
}

void StatsLogProcessor::flushIfNecessary(const EventMetricData& eventMetricData) {
    if (eventMetricData.ByteSize() + mBufferSize > kMaxSerializedBytes) {
      flush();
    }
}

void StatsLogProcessor::flush() {
    StatsLogReport logReport;
    for (string eventBuffer : mEvents) {
        EventMetricData eventFromBuffer;
        eventFromBuffer.ParseFromString(eventBuffer);
        EventMetricData* newEntry = logReport.mutable_event_metrics()->add_data();
        newEntry->CopyFrom(eventFromBuffer);
    }

    const int numBytes = logReport.ByteSize();
    vector<uint8_t> logReportBuffer(numBytes);
    logReport.SerializeToArray(&logReportBuffer[0], numBytes);
    mPushLog(logReportBuffer);
    mEvents.clear();
    mBufferSize = 0;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
