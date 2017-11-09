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
#include "statslog.h"

#include "StatsLogProcessor.h"
#include "metrics/CountMetricProducer.h"
#include "stats_util.h"

#include <log/log_event_list.h>
#include <utils/Errors.h>

using namespace android;
using android::util::FIELD_TYPE_BOOL;
using android::util::FIELD_TYPE_FLOAT;
using android::util::FIELD_TYPE_INT32;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::FIELD_TYPE_STRING;
using android::util::ProtoOutputStream;
using std::make_unique;
using std::unique_ptr;
using std::vector;

namespace android {
namespace os {
namespace statsd {

// for ConfigMetricsReport
const int FIELD_ID_CONFIG_KEY = 1;
const int FIELD_ID_METRICS = 2;
const int FIELD_ID_UID_MAP = 3;
// for ConfigKey
const int FIELD_ID_UID = 1;
const int FIELD_ID_NAME = 1;

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
        flushIfNecessary(msg.GetTimestampNs(), pair.first, pair.second);
    }

    // Hard-coded logic to update the isolated uid's in the uid-map.
    // The field numbers need to be currently updated by hand with stats_events.proto
    if (msg.GetTagId() == android::util::ISOLATED_UID_CHANGED) {
        status_t err = NO_ERROR, err2 = NO_ERROR, err3 = NO_ERROR;
        bool is_create = msg.GetBool(3, &err);
        auto parent_uid = int(msg.GetLong(1, &err2));
        auto isolated_uid = int(msg.GetLong(2, &err3));
        if (err == NO_ERROR && err2 == NO_ERROR && err3 == NO_ERROR) {
            if (is_create) {
                mUidMap->assignIsolatedUid(isolated_uid, parent_uid);
            } else {
                mUidMap->removeIsolatedUid(isolated_uid, parent_uid);
            }
        }
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
        mUidMap->OnConfigUpdated(key);
        mMetricsManagers[key] = std::move(newMetricsManager);
        // Why doesn't this work? mMetricsManagers.insert({key, std::move(newMetricsManager)});
        ALOGD("StatsdConfig valid");
    } else {
        // If there is any error in the config, don't use it.
        ALOGD("StatsdConfig NOT valid");
    }
}

vector<uint8_t> StatsLogProcessor::onDumpReport(const ConfigKey& key) {
    auto it = mMetricsManagers.find(key);
    if (it == mMetricsManagers.end()) {
        ALOGW("Config source %s does not exist", key.ToString().c_str());
        return vector<uint8_t>();
    }

    ProtoOutputStream proto;

    // Fill in ConfigKey.
    long long configKeyToken = proto.start(FIELD_TYPE_MESSAGE | FIELD_ID_CONFIG_KEY);
    proto.write(FIELD_TYPE_INT32 | FIELD_ID_UID, key.GetUid());
    proto.write(FIELD_TYPE_STRING | FIELD_ID_NAME, key.GetName());
    proto.end(configKeyToken);

    // Fill in StatsLogReport's.
    for (auto& m : it->second->onDumpReport()) {
        // Add each vector of StatsLogReport into a repeated field.
        proto.write(FIELD_TYPE_MESSAGE | FIELD_ID_METRICS, reinterpret_cast<char*>(m.get()->data()),
                    m.get()->size());
    }

    // Fill in UidMap.
    auto uidMap = mUidMap->getOutput(key);
    const int uidMapSize = uidMap.ByteSize();
    char uidMapBuffer[uidMapSize];
    uidMap.SerializeToArray(&uidMapBuffer[0], uidMapSize);
    proto.write(FIELD_TYPE_MESSAGE | FIELD_ID_UID_MAP, uidMapBuffer, uidMapSize);

    vector<uint8_t> buffer(proto.size());
    size_t pos = 0;
    auto iter = proto.data();
    while (iter.readBuffer() != NULL) {
        size_t toRead = iter.currentToRead();
        std::memcpy(&buffer[pos], iter.readBuffer(), toRead);
        pos += toRead;
        iter.rp()->move(toRead);
    }

    return buffer;
}

void StatsLogProcessor::OnConfigRemoved(const ConfigKey& key) {
    auto it = mMetricsManagers.find(key);
    if (it != mMetricsManagers.end()) {
        it->second->finish();
        mMetricsManagers.erase(it);
        mUidMap->OnConfigRemoved(key);
    }
    auto flushTime = mLastFlushTimes.find(key);
    if (flushTime != mLastFlushTimes.end()) {
        mLastFlushTimes.erase(flushTime);
    }
}

void StatsLogProcessor::flushIfNecessary(uint64_t timestampNs,
                                         const ConfigKey& key,
                                         const unique_ptr<MetricsManager>& metricsManager) {
    auto lastFlushNs = mLastFlushTimes.find(key);
    if (lastFlushNs != mLastFlushTimes.end()) {
        if (timestampNs - lastFlushNs->second < kMinFlushPeriod) {
            return;
        }
    }

    size_t totalBytes = metricsManager->byteSize();
    if (totalBytes > kMaxSerializedBytes) {
        flush();
        mLastFlushTimes[key] = std::move(timestampNs);
    }
}

void StatsLogProcessor::flush() {
    // TODO: Take ConfigKey as an argument and flush metrics related to the
    // ConfigKey. Also, create a wrapper that holds a repeated field of
    // StatsLogReport's.
    /*
    StatsLogReport logReport;
    const int numBytes = logReport.ByteSize();
    vector<uint8_t> logReportBuffer(numBytes);
    logReport.SerializeToArray(&logReportBuffer[0], numBytes);
    mPushLog(logReportBuffer);
    */
}

}  // namespace statsd
}  // namespace os
}  // namespace android
