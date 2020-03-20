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

#include "anomaly/AlarmMonitor.h"
#include "anomaly/AlarmTracker.h"
#include "anomaly/AnomalyTracker.h"
#include "condition/ConditionTracker.h"
#include "config/ConfigKey.h"
#include "external/StatsPullerManager.h"
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
class MetricsManager : public virtual android::RefBase {
public:
    MetricsManager(const ConfigKey& configKey, const StatsdConfig& config, const int64_t timeBaseNs,
                   const int64_t currentTimeNs, const sp<UidMap>& uidMap,
                   const sp<StatsPullerManager>& pullerManager,
                   const sp<AlarmMonitor>& anomalyAlarmMonitor,
                   const sp<AlarmMonitor>& periodicAlarmMonitor);

    virtual ~MetricsManager();

    // Return whether the configuration is valid.
    bool isConfigValid() const;

    bool checkLogCredentials(const LogEvent& event);

    bool eventSanityCheck(const LogEvent& event);

    void onLogEvent(const LogEvent& event);

    void onAnomalyAlarmFired(
        const int64_t& timestampNs,
        unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>>& alarmSet);

    void onPeriodicAlarmFired(
        const int64_t& timestampNs,
        unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>>& alarmSet);

    void notifyAppUpgrade(const int64_t& eventTimeNs, const string& apk, const int uid,
                          const int64_t version);

    void notifyAppRemoved(const int64_t& eventTimeNs, const string& apk, const int uid);

    void onUidMapReceived(const int64_t& eventTimeNs);

    bool shouldWriteToDisk() const {
        return mNoReportMetricIds.size() != mAllMetricProducers.size();
    }

    bool shouldPersistLocalHistory() const {
        return mShouldPersistHistory;
    }

    void dumpStates(FILE* out, bool verbose);

    inline bool isInTtl(const int64_t timestampNs) const {
        return mTtlNs <= 0 || timestampNs < mTtlEndNs;
    };

    inline bool hashStringInReport() const {
        return mHashStringsInReport;
    };

    inline bool versionStringsInReport() const {
        return mVersionStringsInReport;
    };

    inline bool installerInReport() const {
        return mInstallerInReport;
    };

    void refreshTtl(const int64_t currentTimestampNs) {
        if (mTtlNs > 0) {
            mTtlEndNs = currentTimestampNs + mTtlNs;
        }
    };

    // Returns the elapsed realtime when this metric manager last reported metrics. If this config
    // has not yet dumped any reports, this is the time the metricsmanager was initialized.
    inline int64_t getLastReportTimeNs() const {
        return mLastReportTimeNs;
    };

    inline int64_t getLastReportWallClockNs() const {
        return mLastReportWallClockNs;
    };

    inline size_t getNumMetrics() const {
        return mAllMetricProducers.size();
    }

    virtual void dropData(const int64_t dropTimeNs);

    virtual void onDumpReport(const int64_t dumpTimeNs,
                              const bool include_current_partial_bucket,
                              const bool erase_data,
                              const DumpLatency dumpLatency,
                              std::set<string> *str_set,
                              android::util::ProtoOutputStream* protoOutput);

    // Computes the total byte size of all metrics managed by a single config source.
    // Does not change the state.
    virtual size_t byteSize();

    // Returns whether or not this config is active.
    // The config is active if any metric in the config is active.
    inline bool isActive() const {
        return mIsActive;
    }

    void loadActiveConfig(const ActiveConfig& config, int64_t currentTimeNs);

    void writeActiveConfigToProtoOutputStream(
            int64_t currentTimeNs, const DumpReportReason reason, ProtoOutputStream* proto);

private:
    // For test only.
    inline int64_t getTtlEndNs() const { return mTtlEndNs; }

    const ConfigKey mConfigKey;

    sp<UidMap> mUidMap;

    bool mConfigValid = false;

    bool mHashStringsInReport = false;
    bool mVersionStringsInReport = false;
    bool mInstallerInReport = false;

    const int64_t mTtlNs;
    int64_t mTtlEndNs;

    int64_t mLastReportTimeNs;
    int64_t mLastReportWallClockNs;

    // The uid log sources from StatsdConfig.
    std::vector<int32_t> mAllowedUid;

    // The pkg log sources from StatsdConfig.
    std::vector<std::string> mAllowedPkg;

    // The combined uid sources (after translating pkg name to uid).
    // Logs from uids that are not in the list will be ignored to avoid spamming.
    std::set<int32_t> mAllowedLogSources;

    // Contains the annotations passed in with StatsdConfig.
    std::list<std::pair<const int64_t, const int32_t>> mAnnotations;

    const bool mShouldPersistHistory;

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

    // Hold all periodic alarm trackers.
    std::vector<sp<AlarmTracker>> mAllPeriodicAlarmTrackers;

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

    // Maps from the index of the LogMatchingTracker to index of MetricProducer.
    std::unordered_map<int, std::vector<int>> mTrackerToMetricMap;

    // Maps from LogMatchingTracker to ConditionTracker
    std::unordered_map<int, std::vector<int>> mTrackerToConditionMap;

    // Maps from ConditionTracker to MetricProducer
    std::unordered_map<int, std::vector<int>> mConditionToMetricMap;

    // Maps from life span triggering event to MetricProducers.
    std::unordered_map<int, std::vector<int>> mActivationAtomTrackerToMetricMap;

    // Maps deactivation triggering event to MetricProducers.
    std::unordered_map<int, std::vector<int>> mDeactivationAtomTrackerToMetricMap;

    // Maps AlertIds to the index of the corresponding AnomalyTracker stored in mAllAnomalyTrackers.
    // The map is used in LoadMetadata to more efficiently lookup AnomalyTrackers from an AlertId.
    std::unordered_map<int64_t, int> mAlertTrackerMap;

    std::vector<int> mMetricIndexesWithActivation;

    void initLogSourceWhiteList();

    // The metrics that don't need to be uploaded or even reported.
    std::set<int64_t> mNoReportMetricIds;

   // The config is active if any metric in the config is active.
    bool mIsActive;

    // The config is always active if any metric in the config does not have an activation signal.
    bool mIsAlwaysActive;

    FRIEND_TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensions);
    FRIEND_TEST(MetricConditionLinkE2eTest, TestMultiplePredicatesAndLinks);
    FRIEND_TEST(AttributionE2eTest, TestAttributionMatchAndSliceByFirstUid);
    FRIEND_TEST(AttributionE2eTest, TestAttributionMatchAndSliceByChain);
    FRIEND_TEST(GaugeMetricE2eTest, TestMultipleFieldsForPushedEvent);
    FRIEND_TEST(GaugeMetricE2eTest, TestRandomSamplePulledEvents);
    FRIEND_TEST(GaugeMetricE2eTest, TestRandomSamplePulledEvent_LateAlarm);
    FRIEND_TEST(GaugeMetricE2eTest, TestRandomSamplePulledEventsWithActivation);
    FRIEND_TEST(GaugeMetricE2eTest, TestRandomSamplePulledEventsNoCondition);
    FRIEND_TEST(GaugeMetricE2eTest, TestConditionChangeToTrueSamplePulledEvents);
    FRIEND_TEST(ValueMetricE2eTest, TestPulledEvents);
    FRIEND_TEST(ValueMetricE2eTest, TestPulledEvents_LateAlarm);
    FRIEND_TEST(ValueMetricE2eTest, TestPulledEvents_WithActivation);

    FRIEND_TEST(AnomalyDetectionE2eTest, TestSlicedCountMetric_single_bucket);
    FRIEND_TEST(AnomalyDetectionE2eTest, TestSlicedCountMetric_multiple_buckets);
    FRIEND_TEST(AnomalyDetectionE2eTest, TestDurationMetric_SUM_single_bucket);
    FRIEND_TEST(AnomalyDetectionE2eTest, TestDurationMetric_SUM_multiple_buckets);
    FRIEND_TEST(AnomalyDetectionE2eTest, TestDurationMetric_SUM_long_refractory_period);

    FRIEND_TEST(AlarmE2eTest, TestMultipleAlarms);
    FRIEND_TEST(ConfigTtlE2eTest, TestCountMetric);
    FRIEND_TEST(MetricActivationE2eTest, TestCountMetric);
    FRIEND_TEST(MetricActivationE2eTest, TestCountMetricWithOneDeactivation);
    FRIEND_TEST(MetricActivationE2eTest, TestCountMetricWithTwoDeactivations);
    FRIEND_TEST(MetricActivationE2eTest, TestCountMetricWithSameDeactivation);
    FRIEND_TEST(MetricActivationE2eTest, TestCountMetricWithTwoMetricsTwoDeactivations);

    FRIEND_TEST(StatsLogProcessorTest, TestActiveConfigMetricDiskWriteRead);
    FRIEND_TEST(StatsLogProcessorTest, TestActivationOnBoot);
    FRIEND_TEST(StatsLogProcessorTest, TestActivationOnBootMultipleActivations);
    FRIEND_TEST(StatsLogProcessorTest,
            TestActivationOnBootMultipleActivationsDifferentActivationTypes);
    FRIEND_TEST(StatsLogProcessorTest, TestActivationsPersistAcrossSystemServerRestart);

    FRIEND_TEST(CountMetricE2eTest, TestSlicedState);
    FRIEND_TEST(CountMetricE2eTest, TestSlicedStateWithMap);
    FRIEND_TEST(CountMetricE2eTest, TestMultipleSlicedStates);
    FRIEND_TEST(CountMetricE2eTest, TestSlicedStateWithPrimaryFields);

    FRIEND_TEST(DurationMetricE2eTest, TestOneBucket);
    FRIEND_TEST(DurationMetricE2eTest, TestTwoBuckets);
    FRIEND_TEST(DurationMetricE2eTest, TestWithActivation);
    FRIEND_TEST(DurationMetricE2eTest, TestWithCondition);
    FRIEND_TEST(DurationMetricE2eTest, TestWithSlicedCondition);
    FRIEND_TEST(DurationMetricE2eTest, TestWithActivationAndSlicedCondition);

    FRIEND_TEST(ValueMetricE2eTest, TestInitWithSlicedState);
    FRIEND_TEST(ValueMetricE2eTest, TestInitWithSlicedState_WithDimensions);
    FRIEND_TEST(ValueMetricE2eTest, TestInitWithSlicedState_WithIncorrectDimensions);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
