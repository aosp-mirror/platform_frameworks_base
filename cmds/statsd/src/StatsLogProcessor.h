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
    StatsLogProcessor(const sp<UidMap>& uidMap,
                      const std::function<void(const vector<uint8_t>&)>& pushLog);
    virtual ~StatsLogProcessor();

    virtual void OnLogEvent(const LogEvent& event);

    void OnConfigUpdated(const ConfigKey& key, const StatsdConfig& config);
    void OnConfigRemoved(const ConfigKey& key);

    // TODO: Once we have the ProtoOutputStream in c++, we can just return byte array.
    ConfigMetricsReport onDumpReport(const ConfigKey& key);

    /* Request a flush through a binder call. */
    void flush();

private:
    std::unordered_map<ConfigKey, std::unique_ptr<MetricsManager>> mMetricsManagers;

    std::unordered_map<ConfigKey, long> mLastFlushTimes;

    sp<UidMap> mUidMap;  // Reference to the UidMap to lookup app name and version for each uid.

    /* Max *serialized* size of the logs kept in memory before flushing through binder call.
       Proto lite does not implement the SpaceUsed() function which gives the in memory byte size.
       So we cap memory usage by limiting the serialized size. Note that protobuf's in memory size
       is higher than its serialized size.
     */
    static const size_t kMaxSerializedBytes = 16 * 1024;

    /* Check if the buffer size exceeds the max buffer size when the new entry is added, and flush
       the logs to callback clients if true. */
    void flushIfNecessary(uint64_t timestampNs,
                          const ConfigKey& key,
                          const unique_ptr<MetricsManager>& metricsManager);

    std::function<void(const vector<uint8_t>&)> mPushLog;

    /* Minimum period between two flushes in nanoseconds. Currently set to 10
     * minutes. */
    static const unsigned long long kMinFlushPeriod = 600 * NS_PER_SEC;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATS_LOG_PROCESSOR_H
