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
    StatsLogProcessor(const sp<UidMap>& uidMap,
                      const std::function<void(const vector<uint8_t>&)>& pushLog);
    virtual ~StatsLogProcessor();

    virtual void OnLogEvent(const LogEvent& event);

    void OnConfigUpdated(const ConfigKey& key, const StatsdConfig& config);
    void OnConfigRemoved(const ConfigKey& key);

    // TODO: Once we have the ProtoOutputStream in c++, we can just return byte array.
    std::vector<StatsLogReport> onDumpReport(const ConfigKey& key);

    /* Request a flush through a binder call. */
    void flush();

private:
    // TODO: use EventMetrics to log the events.
    DropboxWriter m_dropbox_writer;

    std::unordered_map<ConfigKey, std::unique_ptr<MetricsManager>> mMetricsManagers;

    sp<UidMap> mUidMap;  // Reference to the UidMap to lookup app name and version for each uid.

    /* Max *serialized* size of the logs kept in memory before flushing through binder call.
       Proto lite does not implement the SpaceUsed() function which gives the in memory byte size.
       So we cap memory usage by limiting the serialized size. Note that protobuf's in memory size
       is higher than its serialized size.
     */
    static const size_t kMaxSerializedBytes = 16 * 1024;

    /* List of data that was captured for a single metric over a given interval of time. */
    vector<string> mEvents;

    /* Current *serialized* size of the logs kept in memory.
       To save computation, we will not calculate the size of the StatsLogReport every time when a
       new entry is added, which would recursively call ByteSize() on every log entry. Instead, we
       keep the sum of all individual stats log entry sizes. The size of a proto is approximately
       the sum of the size of all member protos.
     */
    size_t mBufferSize = 0;

    /* Check if the buffer size exceeds the max buffer size when the new entry is added, and flush
       the logs to dropbox if true. */
    void flushIfNecessary(const EventMetricData& eventMetricData);

    /* Append event metric data to StatsLogReport. */
    void addEventMetricData(const EventMetricData& eventMetricData);

    std::function<void(const vector<uint8_t>&)> mPushLog;
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATS_LOG_PROCESSOR_H
