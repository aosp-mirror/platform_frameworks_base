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

#include <gtest/gtest_prod.h>
#include "config/ConfigListener.h"
#include "logd/LogEvent.h"
#include "metrics/MetricsManager.h"
#include "packages/UidMap.h"
#include "external/StatsPullerManager.h"

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_metadata.pb.h"

#include <stdio.h>
#include <unordered_map>

namespace android {
namespace os {
namespace statsd {


class StatsLogProcessor : public ConfigListener, public virtual PackageInfoListener {
public:
    StatsLogProcessor(const sp<UidMap>& uidMap, const sp<StatsPullerManager>& pullerManager,
                      const sp<AlarmMonitor>& anomalyAlarmMonitor,
                      const sp<AlarmMonitor>& subscriberTriggerAlarmMonitor,
                      const int64_t timeBaseNs,
                      const std::function<bool(const ConfigKey&)>& sendBroadcast,
                      const std::function<bool(const int&,
                                               const vector<int64_t>&)>& sendActivationBroadcast);
    virtual ~StatsLogProcessor();

    void OnLogEvent(LogEvent* event);

    void OnConfigUpdated(const int64_t timestampNs, const ConfigKey& key,
                         const StatsdConfig& config);
    void OnConfigRemoved(const ConfigKey& key);

    size_t GetMetricsSize(const ConfigKey& key) const;

    void GetActiveConfigs(const int uid, vector<int64_t>& outActiveConfigs);

    void onDumpReport(const ConfigKey& key, const int64_t dumpTimeNs,
                      const bool include_current_partial_bucket, const bool erase_data,
                      const DumpReportReason dumpReportReason,
                      const DumpLatency dumpLatency,
                      vector<uint8_t>* outData);
    void onDumpReport(const ConfigKey& key, const int64_t dumpTimeNs,
                      const bool include_current_partial_bucket, const bool erase_data,
                      const DumpReportReason dumpReportReason,
                      const DumpLatency dumpLatency,
                      ProtoOutputStream* proto);

    /* Tells MetricsManager that the alarms in alarmSet have fired. Modifies anomaly alarmSet. */
    void onAnomalyAlarmFired(
            const int64_t& timestampNs,
            unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>> alarmSet);

    /* Tells MetricsManager that the alarms in alarmSet have fired. Modifies periodic alarmSet. */
    void onPeriodicAlarmFired(
            const int64_t& timestampNs,
            unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>> alarmSet);

    /* Flushes data to disk. Data on memory will be gone after written to disk. */
    void WriteDataToDisk(const DumpReportReason dumpReportReason,
                         const DumpLatency dumpLatency);

    /* Persist configs containing metrics with active activations to disk. */
    void SaveActiveConfigsToDisk(int64_t currentTimeNs);

    /* Writes the current active status/ttl for all configs and metrics to ProtoOutputStream. */
    void WriteActiveConfigsToProtoOutputStream(
            int64_t currentTimeNs, const DumpReportReason reason, ProtoOutputStream* proto);

    /* Load configs containing metrics with active activations from disk. */
    void LoadActiveConfigsFromDisk();

    /* Persist metadata for configs and metrics to disk. */
    void SaveMetadataToDisk(int64_t currentWallClockTimeNs, int64_t systemElapsedTimeNs);

    /* Writes the statsd metadata for all configs and metrics to StatsMetadataList. */
    void WriteMetadataToProto(int64_t currentWallClockTimeNs,
                              int64_t systemElapsedTimeNs,
                              metadata::StatsMetadataList* metadataList);

    /* Load stats metadata for configs and metrics from disk. */
    void LoadMetadataFromDisk(int64_t currentWallClockTimeNs,
                              int64_t systemElapsedTimeNs);

    /* Sets the metadata for all configs and metrics */
    void SetMetadataState(const metadata::StatsMetadataList& statsMetadataList,
                          int64_t currentWallClockTimeNs,
                          int64_t systemElapsedTimeNs);

    /* Sets the active status/ttl for all configs and metrics to the status in ActiveConfigList. */
    void SetConfigsActiveState(const ActiveConfigList& activeConfigList, int64_t currentTimeNs);

    /* Notify all MetricsManagers of app upgrades */
    void notifyAppUpgrade(const int64_t& eventTimeNs, const string& apk, const int uid,
                          const int64_t version) override;

    /* Notify all MetricsManagers of app removals */
    void notifyAppRemoved(const int64_t& eventTimeNs, const string& apk, const int uid) override;

    /* Notify all MetricsManagers of uid map snapshots received */
    void onUidMapReceived(const int64_t& eventTimeNs) override;

    // Reset all configs.
    void resetConfigs();

    inline sp<UidMap> getUidMap() {
        return mUidMap;
    }

    void dumpStates(int outFd, bool verbose);

    void informPullAlarmFired(const int64_t timestampNs);

    int64_t getLastReportTimeNs(const ConfigKey& key);

    inline void setPrintLogs(bool enabled) {
#ifdef VERY_VERBOSE_PRINTING
        std::lock_guard<std::mutex> lock(mMetricsMutex);
        mPrintAllLogs = enabled;
#endif
    }

    // Add a specific config key to the possible configs to dump ASAP.
    void noteOnDiskData(const ConfigKey& key);

private:
    // For testing only.
    inline sp<AlarmMonitor> getAnomalyAlarmMonitor() const {
        return mAnomalyAlarmMonitor;
    }

    inline sp<AlarmMonitor> getPeriodicAlarmMonitor() const {
        return mPeriodicAlarmMonitor;
    }

    mutable mutex mMetricsMutex;

    std::unordered_map<ConfigKey, sp<MetricsManager>> mMetricsManagers;

    std::unordered_map<ConfigKey, int64_t> mLastBroadcastTimes;

    // Last time we sent a broadcast to this uid that the active configs had changed.
    std::unordered_map<int, int64_t> mLastActivationBroadcastTimes;

    // Tracks when we last checked the bytes consumed for each config key.
    std::unordered_map<ConfigKey, int64_t> mLastByteSizeTimes;

    // Tracks which config keys has metric reports on disk
    std::set<ConfigKey> mOnDiskDataConfigs;

    sp<UidMap> mUidMap;  // Reference to the UidMap to lookup app name and version for each uid.

    sp<StatsPullerManager> mPullerManager;  // Reference to StatsPullerManager

    sp<AlarmMonitor> mAnomalyAlarmMonitor;

    sp<AlarmMonitor> mPeriodicAlarmMonitor;

    void OnLogEvent(LogEvent* event, int64_t elapsedRealtimeNs);

    void resetIfConfigTtlExpiredLocked(const int64_t timestampNs);

    void OnConfigUpdatedLocked(
        const int64_t currentTimestampNs, const ConfigKey& key, const StatsdConfig& config);

    void GetActiveConfigsLocked(const int uid, vector<int64_t>& outActiveConfigs);

    void WriteActiveConfigsToProtoOutputStreamLocked(
            int64_t currentTimeNs, const DumpReportReason reason, ProtoOutputStream* proto);

    void SetConfigsActiveStateLocked(const ActiveConfigList& activeConfigList,
                                     int64_t currentTimeNs);

    void SetMetadataStateLocked(const metadata::StatsMetadataList& statsMetadataList,
                                int64_t currentWallClockTimeNs,
                                int64_t systemElapsedTimeNs);

    void WriteMetadataToProtoLocked(int64_t currentWallClockTimeNs,
                                    int64_t systemElapsedTimeNs,
                                    metadata::StatsMetadataList* metadataList);

    void WriteDataToDiskLocked(const DumpReportReason dumpReportReason,
                               const DumpLatency dumpLatency);

    void WriteDataToDiskLocked(const ConfigKey& key, const int64_t timestampNs,
                               const DumpReportReason dumpReportReason,
                               const DumpLatency dumpLatency);

    void onConfigMetricsReportLocked(
            const ConfigKey& key, const int64_t dumpTimeStampNs,
            const bool include_current_partial_bucket, const bool erase_data,
            const DumpReportReason dumpReportReason, const DumpLatency dumpLatency,
            /*if dataSavedToDisk is true, it indicates the caller will write the data to disk
             (e.g., before reboot). So no need to further persist local history.*/
            const bool dataSavedToDisk, vector<uint8_t>* proto);

    /* Check if we should send a broadcast if approaching memory limits and if we're over, we
     * actually delete the data. */
    void flushIfNecessaryLocked(const ConfigKey& key, MetricsManager& metricsManager);

    // Maps the isolated uid in the log event to host uid if the log event contains uid fields.
    void mapIsolatedUidToHostUidIfNecessaryLocked(LogEvent* event) const;

    // Handler over the isolated uid change event.
    void onIsolatedUidChangedEventLocked(const LogEvent& event);

    // Handler over the binary push state changed event.
    void onBinaryPushStateChangedEventLocked(LogEvent* event);

    // Handler over the watchdog rollback occurred event.
    void onWatchdogRollbackOccurredLocked(LogEvent* event);

    // Updates train info on disk based on binary push state changed info and
    // write disk info into parameters.
    void getAndUpdateTrainInfoOnDisk(bool is_rollback, InstallTrainInfo* trainInfoIn);

    // Gets experiment ids on disk for associated train and updates them
    // depending on rollback type. Then writes them back to disk and returns
    // them.
    std::vector<int64_t> processWatchdogRollbackOccurred(const int32_t rollbackTypeIn,
                                                          const string& packageName);

    // Reset all configs.
    void resetConfigsLocked(const int64_t timestampNs);
    // Reset the specified configs.
    void resetConfigsLocked(const int64_t timestampNs, const std::vector<ConfigKey>& configs);

    // Function used to send a broadcast so that receiver for the config key can call getData
    // to retrieve the stored data.
    std::function<bool(const ConfigKey& key)> mSendBroadcast;

    // Function used to send a broadcast so that receiver can be notified of which configs
    // are currently active.
    std::function<bool(const int& uid, const vector<int64_t>& configIds)> mSendActivationBroadcast;

    const int64_t mTimeBaseNs;

    // Largest timestamp of the events that we have processed.
    int64_t mLargestTimestampSeen = 0;

    int64_t mLastTimestampSeen = 0;

    int64_t mLastPullerCacheClearTimeSec = 0;

    // Last time we wrote data to disk.
    int64_t mLastWriteTimeNs = 0;

    // Last time we wrote active metrics to disk.
    int64_t mLastActiveMetricsWriteNs = 0;

    //Last time we wrote metadata to disk.
    int64_t mLastMetadataWriteNs = 0;

#ifdef VERY_VERBOSE_PRINTING
    bool mPrintAllLogs = false;
#endif

    FRIEND_TEST(StatsLogProcessorTest, TestOutOfOrderLogs);
    FRIEND_TEST(StatsLogProcessorTest, TestRateLimitByteSize);
    FRIEND_TEST(StatsLogProcessorTest, TestRateLimitBroadcast);
    FRIEND_TEST(StatsLogProcessorTest, TestDropWhenByteSizeTooLarge);
    FRIEND_TEST(StatsLogProcessorTest, TestActiveConfigMetricDiskWriteRead);
    FRIEND_TEST(StatsLogProcessorTest, TestActivationOnBoot);
    FRIEND_TEST(StatsLogProcessorTest, TestActivationOnBootMultipleActivations);
    FRIEND_TEST(StatsLogProcessorTest,
            TestActivationOnBootMultipleActivationsDifferentActivationTypes);
    FRIEND_TEST(StatsLogProcessorTest, TestActivationsPersistAcrossSystemServerRestart);

    FRIEND_TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensionsForSumDuration1);
    FRIEND_TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensionsForSumDuration2);
    FRIEND_TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensionsForSumDuration3);
    FRIEND_TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensionsForMaxDuration1);
    FRIEND_TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensionsForMaxDuration2);
    FRIEND_TEST(WakelockDurationE2eTest, TestAggregatedPredicateDimensionsForMaxDuration3);
    FRIEND_TEST(MetricConditionLinkE2eTest, TestMultiplePredicatesAndLinks1);
    FRIEND_TEST(MetricConditionLinkE2eTest, TestMultiplePredicatesAndLinks2);
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
    FRIEND_TEST(AnomalyDetectionE2eTest, TestCountMetric_save_refractory_to_disk_no_data_written);
    FRIEND_TEST(AnomalyDetectionE2eTest, TestCountMetric_save_refractory_to_disk);
    FRIEND_TEST(AnomalyDetectionE2eTest, TestCountMetric_load_refractory_from_disk);
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
    FRIEND_TEST(DurationMetricE2eTest, TestWithSlicedState);
    FRIEND_TEST(DurationMetricE2eTest, TestWithConditionAndSlicedState);
    FRIEND_TEST(DurationMetricE2eTest, TestWithSlicedStateMapped);
    FRIEND_TEST(DurationMetricE2eTest, TestSlicedStatePrimaryFieldsNotSubsetDimInWhat);
    FRIEND_TEST(DurationMetricE2eTest, TestWithSlicedStatePrimaryFieldsSubset);

    FRIEND_TEST(ValueMetricE2eTest, TestInitWithSlicedState);
    FRIEND_TEST(ValueMetricE2eTest, TestInitWithSlicedState_WithDimensions);
    FRIEND_TEST(ValueMetricE2eTest, TestInitWithSlicedState_WithIncorrectDimensions);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
