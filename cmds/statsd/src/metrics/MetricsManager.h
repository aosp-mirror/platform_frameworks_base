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

#pragma once

#include "anomaly/AnomalyMonitor.h"
#include "anomaly/AnomalyTracker.h"
#include "condition/ConditionTracker.h"
#include "config/ConfigKey.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "logd/LogEvent.h"
#include "matchers/LogMatchingTracker.h"
#include "metrics/MetricProducer.h"
#include "packages/UidMap.h"

#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

// A MetricsManager is responsible for managing metrics from one single config source.
class MetricsManager : public PackageInfoListener {
public:
    MetricsManager(const ConfigKey& configKey, const StatsdConfig& config, const long timeBaseSec,
                   sp<UidMap> uidMap);

    virtual ~MetricsManager();

    // Return whether the configuration is valid.
    bool isConfigValid() const;

    void onLogEvent(const LogEvent& event);

    void onAnomalyAlarmFired(
        const uint64_t timestampNs,
        unordered_set<sp<const AnomalyAlarm>, SpHash<AnomalyAlarm>>& anomalySet);

    void setAnomalyMonitor(const sp<AnomalyMonitor>& anomalyMonitor);

    void notifyAppUpgrade(const string& apk, const int uid, const int64_t version) override;

    void notifyAppRemoved(const string& apk, const int uid) override;

    void onUidMapReceived() override;

    bool shouldAddUidMapListener() const {
        return !mAllowedPkg.empty();
    }

    void dumpStates(FILE* out, bool verbose);

    // Config source owner can call onDumpReport() to get all the metrics collected.
    virtual void onDumpReport(android::util::ProtoOutputStream* protoOutput);
    virtual void onDumpReport(const uint64_t& dumpTimeStampNs, ConfigMetricsReport* report);

    // Computes the total byte size of all metrics managed by a single config source.
    // Does not change the state.
    virtual size_t byteSize();
private:
    const ConfigKey mConfigKey;

    sp<UidMap> mUidMap;

    // The uid of statsd.
    const int32_t mStatsdUid;

    bool mConfigValid = false;

    // The uid log sources from StatsdConfig.
    std::vector<int32_t> mAllowedUid;

    // The pkg log sources from StatsdConfig.
    std::vector<std::string> mAllowedPkg;

    // The combined uid sources (after translating pkg name to uid).
    // Logs from uids that are not in the list will be ignored to avoid spamming.
    std::set<int32_t> mAllowedLogSources;

    // To guard access to mAllowedLogSources
    mutable std::mutex mAllowedLogSourcesMutex;

    // All event tags that are interesting to my metrics.
    std::set<int> mTagIds;

    // We only store the sp of LogMatchingTracker, MetricProducer, and ConditionTracker in
    // MetricsManager. There are relationships between them, and the relationships are denoted by
    // index instead of pointers. The reasons for this are: (1) the relationship between them are
    // complicated, so storing index instead of pointers reduces the risk that A holds B's sp, and B
    // holds A's sp. (2) When we evaluate matcher results, or condition results, we can quickly get
    // the related results from a cache using the index.

    // Hold all the atom matchers from the config.
    std::vector<sp<LogMatchingTracker>> mAllAtomMatchers;

    // Hold all the conditions from the config.
    std::vector<sp<ConditionTracker>> mAllConditionTrackers;

    // Hold all metrics from the config.
    std::vector<sp<MetricProducer>> mAllMetricProducers;

    // Hold all alert trackers.
    std::vector<sp<AnomalyTracker>> mAllAnomalyTrackers;

    // To make the log processing more efficient, we want to do as much filtering as possible
    // before we go into individual trackers and conditions to match.

    // 1st filter: check if the event tag id is in mTagIds.
    // 2nd filter: if it is, we parse the event because there is at least one member is interested.
    //             then pass to all LogMatchingTrackers (itself also filter events by ids).
    // 3nd filter: for LogMatchingTrackers that matched this event, we pass this event to the
    //             ConditionTrackers and MetricProducers that use this matcher.
    // 4th filter: for ConditionTrackers that changed value due to this event, we pass
    //             new conditions to  metrics that use this condition.

    // The following map is initialized from the statsd_config.

    // maps from the index of the LogMatchingTracker to index of MetricProducer.
    std::unordered_map<int, std::vector<int>> mTrackerToMetricMap;

    // maps from LogMatchingTracker to ConditionTracker
    std::unordered_map<int, std::vector<int>> mTrackerToConditionMap;

    // maps from ConditionTracker to MetricProducer
    std::unordered_map<int, std::vector<int>> mConditionToMetricMap;

    void initLogSourceWhiteList();

    // Fetches the uid of statsd from UidMap.
    static int32_t getStatsdUid();

    // The metrics that don't need to be uploaded or even reported.
    std::set<int64_t> mNoReportMetricIds;

    FRIEND_TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensions);
    FRIEND_TEST(MetricConditionLinkE2eTest, TestMultiplePredicatesAndLinks);
    FRIEND_TEST(AttributionE2eTest, TestAttributionMatchAndSlice);
    FRIEND_TEST(GaugeMetricE2eTest, TestMultipleFieldsForPushedEvent);
    FRIEND_TEST(DimensionInConditionE2eTest, TestCountMetricNoLink);
    FRIEND_TEST(DimensionInConditionE2eTest, TestCountMetricWithLink);
    FRIEND_TEST(DimensionInConditionE2eTest, TestDurationMetricNoLink);
    FRIEND_TEST(DimensionInConditionE2eTest, TestDurationMetricWithLink);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
