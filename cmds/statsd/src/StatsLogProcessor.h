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

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

#include <stdio.h>
#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

class StatsLogProcessor : public ConfigListener {
public:
    StatsLogProcessor(const sp<UidMap>& uidMap, const sp<AnomalyMonitor>& anomalyMonitor,
                      const std::function<void(const ConfigKey&)>& sendBroadcast);
    virtual ~StatsLogProcessor();

    virtual void OnLogEvent(const LogEvent& event);

    void OnConfigUpdated(const ConfigKey& key, const StatsdConfig& config);
    void OnConfigRemoved(const ConfigKey& key);

    size_t GetMetricsSize(const ConfigKey& key) const;

    void onDumpReport(const ConfigKey& key, vector<uint8_t>* outData);
    void onAnomalyAlarmFired(
            const uint64_t timestampNs,
            unordered_set<sp<const AnomalyAlarm>, SpHash<AnomalyAlarm>> anomalySet);

    /* Flushes data to disk. Data on memory will be gone after written to disk. */
    void WriteDataToDisk();

private:
    mutable mutex mBroadcastTimesMutex;

    std::unordered_map<ConfigKey, std::unique_ptr<MetricsManager>> mMetricsManagers;

    std::unordered_map<ConfigKey, long> mLastBroadcastTimes;

    sp<UidMap> mUidMap;  // Reference to the UidMap to lookup app name and version for each uid.

    sp<AnomalyMonitor> mAnomalyMonitor;

    /* Max *serialized* size of the logs kept in memory before flushing through binder call.
       Proto lite does not implement the SpaceUsed() function which gives the in memory byte size.
       So we cap memory usage by limiting the serialized size. Note that protobuf's in memory size
       is higher than its serialized size.
     */
    static const size_t kMaxSerializedBytes = 16 * 1024;

    /* Check if we should send a broadcast if approaching memory limits and if we're over, we
     * actually delete the data. */
    void flushIfNecessary(uint64_t timestampNs,
                          const ConfigKey& key,
                          const unique_ptr<MetricsManager>& metricsManager);

    // Function used to send a broadcast so that receiver for the config key can call getData
    // to retrieve the stored data.
    std::function<void(const ConfigKey& key)> mSendBroadcast;

    /* Minimum period between two broadcasts in nanoseconds. Currently set to 60 seconds. */
    static const unsigned long long kMinBroadcastPeriod = 60 * NS_PER_SEC;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATS_LOG_PROCESSOR_H
