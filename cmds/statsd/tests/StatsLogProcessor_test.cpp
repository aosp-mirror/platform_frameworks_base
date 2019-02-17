// Copyright (C) 2017 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "StatsLogProcessor.h"
#include "config/ConfigKey.h"
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "guardrail/StatsdStats.h"
#include "logd/LogEvent.h"
#include "packages/UidMap.h"
#include "statslog.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include "tests/statsd_test_util.h"

#include <stdio.h>

using namespace android;
using namespace testing;

namespace android {
namespace os {
namespace statsd {

using android::util::ProtoOutputStream;

#ifdef __ANDROID__

/**
 * Mock MetricsManager (ByteSize() is called).
 */
class MockMetricsManager : public MetricsManager {
public:
    MockMetricsManager()
        : MetricsManager(ConfigKey(1, 12345), StatsdConfig(), 1000, 1000, new UidMap(),
                         new StatsPullerManager(),
                         new AlarmMonitor(10, [](const sp<IStatsCompanionService>&, int64_t) {},
                                          [](const sp<IStatsCompanionService>&) {}),
                         new AlarmMonitor(10, [](const sp<IStatsCompanionService>&, int64_t) {},
                                          [](const sp<IStatsCompanionService>&) {})) {
    }

    MOCK_METHOD0(byteSize, size_t());

    MOCK_METHOD1(dropData, void(const int64_t dropTimeNs));
};

TEST(StatsLogProcessorTest, TestRateLimitByteSize) {
    sp<UidMap> m = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;
    // Construct the processor with a dummy sendBroadcast function that does nothing.
    StatsLogProcessor p(m, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor, 0,
                        [](const ConfigKey& key) { return true; },
                        [](const int&, const vector<int64_t>&) {return true;});

    MockMetricsManager mockMetricsManager;

    ConfigKey key(100, 12345);
    // Expect only the first flush to trigger a check for byte size since the last two are
    // rate-limited.
    EXPECT_CALL(mockMetricsManager, byteSize()).Times(1);
    p.flushIfNecessaryLocked(99, key, mockMetricsManager);
    p.flushIfNecessaryLocked(100, key, mockMetricsManager);
    p.flushIfNecessaryLocked(101, key, mockMetricsManager);
}

TEST(StatsLogProcessorTest, TestRateLimitBroadcast) {
    sp<UidMap> m = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    int broadcastCount = 0;
    StatsLogProcessor p(m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor, 0,
                        [&broadcastCount](const ConfigKey& key) {
                            broadcastCount++;
                            return true;
                        },
                        [](const int&, const vector<int64_t>&) {return true;});

    MockMetricsManager mockMetricsManager;

    ConfigKey key(100, 12345);
    EXPECT_CALL(mockMetricsManager, byteSize())
            .Times(1)
            .WillRepeatedly(Return(int(StatsdStats::kMaxMetricsBytesPerConfig * .95)));

    // Expect only one broadcast despite always returning a size that should trigger broadcast.
    p.flushIfNecessaryLocked(1, key, mockMetricsManager);
    EXPECT_EQ(1, broadcastCount);

    // b/73089712
    // This next call to flush should not trigger a broadcast.
    // p.mLastByteSizeTimes.clear();  // Force another check for byte size.
    // p.flushIfNecessaryLocked(2, key, mockMetricsManager);
    // EXPECT_EQ(1, broadcastCount);
}

TEST(StatsLogProcessorTest, TestDropWhenByteSizeTooLarge) {
    sp<UidMap> m = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    int broadcastCount = 0;
    StatsLogProcessor p(m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor, 0,
                        [&broadcastCount](const ConfigKey& key) {
                            broadcastCount++;
                            return true;
                        },
                        [](const int&, const vector<int64_t>&) {return true;});

    MockMetricsManager mockMetricsManager;

    ConfigKey key(100, 12345);
    EXPECT_CALL(mockMetricsManager, byteSize())
            .Times(1)
            .WillRepeatedly(Return(int(StatsdStats::kMaxMetricsBytesPerConfig * 1.2)));

    EXPECT_CALL(mockMetricsManager, dropData(_)).Times(1);

    // Expect to call the onDumpReport and skip the broadcast.
    p.flushIfNecessaryLocked(1, key, mockMetricsManager);
    EXPECT_EQ(0, broadcastCount);
}

StatsdConfig MakeConfig(bool includeMetric) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    if (includeMetric) {
        auto appCrashMatcher = CreateProcessCrashAtomMatcher();
        *config.add_atom_matcher() = appCrashMatcher;
        auto countMetric = config.add_count_metric();
        countMetric->set_id(StringToId("AppCrashes"));
        countMetric->set_what(appCrashMatcher.id());
        countMetric->set_bucket(FIVE_MINUTES);
    }
    return config;
}

TEST(StatsLogProcessorTest, TestUidMapHasSnapshot) {
    // Setup simple config key corresponding to empty config.
    sp<UidMap> m = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    m->updateMap(1, {1, 2}, {1, 2}, {String16("v1"), String16("v2")},
                 {String16("p1"), String16("p2")}, {String16(""), String16("")});
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    int broadcastCount = 0;
    StatsLogProcessor p(m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor, 0,
                        [&broadcastCount](const ConfigKey& key) {
                            broadcastCount++;
                            return true;
                        },
                        [](const int&, const vector<int64_t>&) {return true;});
    ConfigKey key(3, 4);
    StatsdConfig config = MakeConfig(true);
    p.OnConfigUpdated(0, key, config);

    // Expect to get no metrics, but snapshot specified above in uidmap.
    vector<uint8_t> bytes;
    p.onDumpReport(key, 1, false, true, ADB_DUMP, &bytes);

    ConfigMetricsReportList output;
    output.ParseFromArray(bytes.data(), bytes.size());
    EXPECT_TRUE(output.reports_size() > 0);
    auto uidmap = output.reports(0).uid_map();
    EXPECT_TRUE(uidmap.snapshots_size() > 0);
    EXPECT_EQ(2, uidmap.snapshots(0).package_info_size());
}

TEST(StatsLogProcessorTest, TestEmptyConfigHasNoUidMap) {
    // Setup simple config key corresponding to empty config.
    sp<UidMap> m = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    m->updateMap(1, {1, 2}, {1, 2}, {String16("v1"), String16("v2")},
                 {String16("p1"), String16("p2")}, {String16(""), String16("")});
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    int broadcastCount = 0;
    StatsLogProcessor p(m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor, 0,
                        [&broadcastCount](const ConfigKey& key) {
                            broadcastCount++;
                            return true;
                        },
                        [](const int&, const vector<int64_t>&) {return true;});
    ConfigKey key(3, 4);
    StatsdConfig config = MakeConfig(false);
    p.OnConfigUpdated(0, key, config);

    // Expect to get no metrics, but snapshot specified above in uidmap.
    vector<uint8_t> bytes;
    p.onDumpReport(key, 1, false, true, ADB_DUMP, &bytes);

    ConfigMetricsReportList output;
    output.ParseFromArray(bytes.data(), bytes.size());
    EXPECT_TRUE(output.reports_size() > 0);
    EXPECT_FALSE(output.reports(0).has_uid_map());
}

TEST(StatsLogProcessorTest, TestReportIncludesSubConfig) {
    // Setup simple config key corresponding to empty config.
    sp<UidMap> m = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    int broadcastCount = 0;
    StatsLogProcessor p(m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor, 0,
                        [&broadcastCount](const ConfigKey& key) {
                            broadcastCount++;
                            return true;
                        },
                        [](const int&, const vector<int64_t>&) {return true;});
    ConfigKey key(3, 4);
    StatsdConfig config;
    auto annotation = config.add_annotation();
    annotation->set_field_int64(1);
    annotation->set_field_int32(2);
    config.add_allowed_log_source("AID_ROOT");
    p.OnConfigUpdated(1, key, config);

    // Expect to get no metrics, but snapshot specified above in uidmap.
    vector<uint8_t> bytes;
    p.onDumpReport(key, 1, false, true, ADB_DUMP, &bytes);

    ConfigMetricsReportList output;
    output.ParseFromArray(bytes.data(), bytes.size());
    EXPECT_TRUE(output.reports_size() > 0);
    auto report = output.reports(0);
    EXPECT_EQ(1, report.annotation_size());
    EXPECT_EQ(1, report.annotation(0).field_int64());
    EXPECT_EQ(2, report.annotation(0).field_int32());
}

TEST(StatsLogProcessorTest, TestOnDumpReportEraseData) {
    // Setup a simple config.
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto wakelockAcquireMatcher = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = wakelockAcquireMatcher;

    auto countMetric = config.add_count_metric();
    countMetric->set_id(123456);
    countMetric->set_what(wakelockAcquireMatcher.id());
    countMetric->set_bucket(FIVE_MINUTES);

    ConfigKey cfgKey;
    sp<StatsLogProcessor> processor = CreateStatsLogProcessor(1, 1, config, cfgKey);

    std::vector<AttributionNodeInternal> attributions1 = {CreateAttribution(111, "App1")};
    auto event = CreateAcquireWakelockEvent(attributions1, "wl1", 2);
    processor->OnLogEvent(event.get());

    vector<uint8_t> bytes;
    ConfigMetricsReportList output;

    // Dump report WITHOUT erasing data.
    processor->onDumpReport(cfgKey, 3, true, false /* Do NOT erase data. */, ADB_DUMP, &bytes);
    output.ParseFromArray(bytes.data(), bytes.size());
    EXPECT_EQ(output.reports_size(), 1);
    EXPECT_EQ(output.reports(0).metrics_size(), 1);
    EXPECT_EQ(output.reports(0).metrics(0).count_metrics().data_size(), 1);

    // Dump report WITH erasing data. There should be data since we didn't previously erase it.
    processor->onDumpReport(cfgKey, 4, true, true /* DO erase data. */, ADB_DUMP, &bytes);
    output.ParseFromArray(bytes.data(), bytes.size());
    EXPECT_EQ(output.reports_size(), 1);
    EXPECT_EQ(output.reports(0).metrics_size(), 1);
    EXPECT_EQ(output.reports(0).metrics(0).count_metrics().data_size(), 1);

    // Dump report again. There should be no data since we erased it.
    processor->onDumpReport(cfgKey, 5, true, true /* DO erase data. */, ADB_DUMP, &bytes);
    output.ParseFromArray(bytes.data(), bytes.size());
    // We don't care whether statsd has a report, as long as it has no count metrics in it.
    bool noData = output.reports_size() == 0
            || output.reports(0).metrics_size() == 0
            || output.reports(0).metrics(0).count_metrics().data_size() == 0;
    EXPECT_TRUE(noData);
}

TEST(StatsLogProcessorTest, TestActiveConfigMetricDiskWriteRead) {
    int uid = 1111;

    // Setup a simple config, no activation
    StatsdConfig config1;
    int64_t cfgId1 = 12341;
    config1.set_id(cfgId1);
    config1.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.
    auto wakelockAcquireMatcher = CreateAcquireWakelockAtomMatcher();
    *config1.add_atom_matcher() = wakelockAcquireMatcher;

    long metricId1 = 1234561;
    long metricId2 = 1234562;
    auto countMetric1 = config1.add_count_metric();
    countMetric1->set_id(metricId1);
    countMetric1->set_what(wakelockAcquireMatcher.id());
    countMetric1->set_bucket(FIVE_MINUTES);

    auto countMetric2 = config1.add_count_metric();
    countMetric2->set_id(metricId2);
    countMetric2->set_what(wakelockAcquireMatcher.id());
    countMetric2->set_bucket(FIVE_MINUTES);

    ConfigKey cfgKey1(uid, cfgId1);

    // Add another config, with two metrics, one with activation
    StatsdConfig config2;
    int64_t cfgId2 = 12342;
    config2.set_id(cfgId2);
    config2.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.
    *config2.add_atom_matcher() = wakelockAcquireMatcher;

    long metricId3 = 1234561;
    long metricId4 = 1234562;

    auto countMetric3 = config2.add_count_metric();
    countMetric3->set_id(metricId3);
    countMetric3->set_what(wakelockAcquireMatcher.id());
    countMetric3->set_bucket(FIVE_MINUTES);

    auto countMetric4 = config2.add_count_metric();
    countMetric4->set_id(metricId4);
    countMetric4->set_what(wakelockAcquireMatcher.id());
    countMetric4->set_bucket(FIVE_MINUTES);

    auto metric3Activation = config2.add_metric_activation();
    metric3Activation->set_metric_id(metricId3);
    auto metric3ActivationTrigger = metric3Activation->add_event_activation();
    metric3ActivationTrigger->set_atom_matcher_id(wakelockAcquireMatcher.id());
    metric3ActivationTrigger->set_ttl_seconds(100);

    ConfigKey cfgKey2(uid, cfgId2);

    // Add another config, with two metrics, both with activations
    StatsdConfig config3;
    int64_t cfgId3 = 12343;
    config3.set_id(cfgId3);
    config3.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.
    *config3.add_atom_matcher() = wakelockAcquireMatcher;

    long metricId5 = 1234565;
    long metricId6 = 1234566;
    auto countMetric5 = config3.add_count_metric();
    countMetric5->set_id(metricId5);
    countMetric5->set_what(wakelockAcquireMatcher.id());
    countMetric5->set_bucket(FIVE_MINUTES);

    auto countMetric6 = config3.add_count_metric();
    countMetric6->set_id(metricId6);
    countMetric6->set_what(wakelockAcquireMatcher.id());
    countMetric6->set_bucket(FIVE_MINUTES);

    auto metric5Activation = config3.add_metric_activation();
    metric5Activation->set_metric_id(metricId5);
    auto metric5ActivationTrigger = metric5Activation->add_event_activation();
    metric5ActivationTrigger->set_atom_matcher_id(wakelockAcquireMatcher.id());
    metric5ActivationTrigger->set_ttl_seconds(100);

    auto metric6Activation = config3.add_metric_activation();
    metric6Activation->set_metric_id(metricId6);
    auto metric6ActivationTrigger = metric6Activation->add_event_activation();
    metric6ActivationTrigger->set_atom_matcher_id(wakelockAcquireMatcher.id());
    metric6ActivationTrigger->set_ttl_seconds(200);

    ConfigKey cfgKey3(uid, cfgId3);

    sp<UidMap> m = new UidMap();
    sp<StatsPullerManager> pullerManager = new StatsPullerManager();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    vector<int64_t> activeConfigsBroadcast;

    long timeBase1 = 1;
    int broadcastCount = 0;
    StatsLogProcessor processor(m, pullerManager, anomalyAlarmMonitor, subscriberAlarmMonitor,
            timeBase1, [](const ConfigKey& key) { return true; },
            [&uid, &broadcastCount, &activeConfigsBroadcast](const int& broadcastUid,
                    const vector<int64_t>& activeConfigs) {
                broadcastCount++;
                EXPECT_EQ(broadcastUid, uid);
                activeConfigsBroadcast.clear();
                activeConfigsBroadcast.insert(activeConfigsBroadcast.end(),
                        activeConfigs.begin(), activeConfigs.end());
                return true;
            });

    processor.OnConfigUpdated(1, cfgKey1, config1);
    processor.OnConfigUpdated(2, cfgKey2, config2);
    processor.OnConfigUpdated(3, cfgKey3, config3);

    EXPECT_EQ(3, processor.mMetricsManagers.size());

    // Expect the first config and both metrics in it to be active.
    auto it = processor.mMetricsManagers.find(cfgKey1);
    EXPECT_TRUE(it != processor.mMetricsManagers.end());
    auto& metricsManager1 = it->second;
    EXPECT_TRUE(metricsManager1->isActive());

    auto metricIt = metricsManager1->mAllMetricProducers.begin();
    for (; metricIt != metricsManager1->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId1) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager1->mAllMetricProducers.end());
    auto& metricProducer1 = *metricIt;
    EXPECT_TRUE(metricProducer1->isActive());

    metricIt = metricsManager1->mAllMetricProducers.begin();
    for (; metricIt != metricsManager1->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId2) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager1->mAllMetricProducers.end());
    auto& metricProducer2 = *metricIt;
    EXPECT_TRUE(metricProducer2->isActive());

    // Expect config 2 to be active. Metric 3 shouldn't be active, metric 4 should be active.
    it = processor.mMetricsManagers.find(cfgKey2);
    EXPECT_TRUE(it != processor.mMetricsManagers.end());
    auto& metricsManager2 = it->second;
    EXPECT_TRUE(metricsManager2->isActive());

    metricIt = metricsManager2->mAllMetricProducers.begin();
    for (; metricIt != metricsManager2->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId3) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager2->mAllMetricProducers.end());
    auto& metricProducer3 = *metricIt;
    EXPECT_FALSE(metricProducer3->isActive());

    metricIt = metricsManager2->mAllMetricProducers.begin();
    for (; metricIt != metricsManager2->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId4) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager2->mAllMetricProducers.end());
    auto& metricProducer4 = *metricIt;
    EXPECT_TRUE(metricProducer4->isActive());

    // Expect the third config and both metrics in it to be inactive.
    it = processor.mMetricsManagers.find(cfgKey3);
    EXPECT_TRUE(it != processor.mMetricsManagers.end());
    auto& metricsManager3 = it->second;
    EXPECT_FALSE(metricsManager3->isActive());

    metricIt = metricsManager3->mAllMetricProducers.begin();
    for (; metricIt != metricsManager2->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId5) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager3->mAllMetricProducers.end());
    auto& metricProducer5 = *metricIt;
    EXPECT_FALSE(metricProducer5->isActive());

    metricIt = metricsManager3->mAllMetricProducers.begin();
    for (; metricIt != metricsManager3->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId6) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager3->mAllMetricProducers.end());
    auto& metricProducer6 = *metricIt;
    EXPECT_FALSE(metricProducer6->isActive());

    // No broadcast for active configs should have happened yet.
    EXPECT_EQ(broadcastCount, 0);

    // Activate all 3 metrics that were not active.
    std::vector<AttributionNodeInternal> attributions1 = {CreateAttribution(111, "App1")};
    auto event = CreateAcquireWakelockEvent(attributions1, "wl1", 100 + timeBase1);
    processor.OnLogEvent(event.get());

    // Assert that all 3 configs are active.
    EXPECT_TRUE(metricsManager1->isActive());
    EXPECT_TRUE(metricsManager2->isActive());
    EXPECT_TRUE(metricsManager3->isActive());

    // A broadcast should have happened, and all 3 configs should be active in the broadcast.
    EXPECT_EQ(broadcastCount, 1);
    EXPECT_EQ(activeConfigsBroadcast.size(), 3);
    EXPECT_TRUE(std::find(activeConfigsBroadcast.begin(), activeConfigsBroadcast.end(), cfgId1)
            != activeConfigsBroadcast.end());
    EXPECT_TRUE(std::find(activeConfigsBroadcast.begin(), activeConfigsBroadcast.end(), cfgId2)
            != activeConfigsBroadcast.end());
    EXPECT_TRUE(std::find(activeConfigsBroadcast.begin(), activeConfigsBroadcast.end(), cfgId3)
            != activeConfigsBroadcast.end());

    // When we shut down, metrics 3 & 5 have 100ns remaining, metric 6 has 100s + 100ns.
    int64_t shutDownTime = timeBase1 + 100 * NS_PER_SEC;
    EXPECT_TRUE(metricProducer3->isActive());
    int64_t ttl3 = metricProducer3->getRemainingTtlNs(shutDownTime);
    EXPECT_EQ(100, ttl3);
    EXPECT_TRUE(metricProducer5->isActive());
    int64_t ttl5 = metricProducer5->getRemainingTtlNs(shutDownTime);
    EXPECT_EQ(100, ttl5);
    EXPECT_TRUE(metricProducer6->isActive());
    int64_t ttl6 = metricProducer6->getRemainingTtlNs(shutDownTime);
    EXPECT_EQ(100 + 100 * NS_PER_SEC, ttl6);

    processor.WriteMetricsActivationToDisk(shutDownTime);

    // Create a second StatsLogProcessor and push the same 3 configs.
    long timeBase2 = 1000;
    sp<StatsLogProcessor> processor2 =
            CreateStatsLogProcessor(timeBase2, timeBase2, config1, cfgKey1);
    processor2->OnConfigUpdated(timeBase2, cfgKey2, config2);
    processor2->OnConfigUpdated(timeBase2, cfgKey3, config3);

    EXPECT_EQ(3, processor2->mMetricsManagers.size());

    // First config and both metrics are active.
    it = processor2->mMetricsManagers.find(cfgKey1);
    EXPECT_TRUE(it != processor2->mMetricsManagers.end());
    auto& metricsManager1001 = it->second;
    EXPECT_TRUE(metricsManager1001->isActive());

    metricIt = metricsManager1001->mAllMetricProducers.begin();
    for (; metricIt != metricsManager1001->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId1) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager1001->mAllMetricProducers.end());
    auto& metricProducer1001 = *metricIt;
    EXPECT_TRUE(metricProducer1001->isActive());

    metricIt = metricsManager1001->mAllMetricProducers.begin();
    for (; metricIt != metricsManager1001->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId2) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager1001->mAllMetricProducers.end());
    auto& metricProducer1002 = *metricIt;
    EXPECT_TRUE(metricProducer1002->isActive());

    // Second config is active. Metric 3 is inactive, metric 4 is active.
    it = processor2->mMetricsManagers.find(cfgKey2);
    EXPECT_TRUE(it != processor2->mMetricsManagers.end());
    auto& metricsManager1002 = it->second;
    EXPECT_TRUE(metricsManager1002->isActive());

    metricIt = metricsManager1002->mAllMetricProducers.begin();
    for (; metricIt != metricsManager1002->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId3) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager1002->mAllMetricProducers.end());
    auto& metricProducer1003 = *metricIt;
    EXPECT_FALSE(metricProducer1003->isActive());

    metricIt = metricsManager1002->mAllMetricProducers.begin();
    for (; metricIt != metricsManager1002->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId4) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager1002->mAllMetricProducers.end());
    auto& metricProducer1004 = *metricIt;
    EXPECT_TRUE(metricProducer1004->isActive());

    // Config 3 is inactive. both metrics are inactive.
    it = processor2->mMetricsManagers.find(cfgKey3);
    EXPECT_TRUE(it != processor2->mMetricsManagers.end());
    auto& metricsManager1003 = it->second;
    EXPECT_FALSE(metricsManager1003->isActive());
    EXPECT_EQ(2, metricsManager1003->mAllMetricProducers.size());

    metricIt = metricsManager1003->mAllMetricProducers.begin();
    for (; metricIt != metricsManager1002->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId5) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager1003->mAllMetricProducers.end());
    auto& metricProducer1005 = *metricIt;
    EXPECT_FALSE(metricProducer1005->isActive());

    metricIt = metricsManager1003->mAllMetricProducers.begin();
    for (; metricIt != metricsManager1003->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId6) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager1003->mAllMetricProducers.end());
    auto& metricProducer1006 = *metricIt;
    EXPECT_FALSE(metricProducer1006->isActive());

    // Assert that all 3 metrics with activation are inactive and that the ttls were properly set.
    EXPECT_FALSE(metricProducer1003->isActive());
    const auto& activation1003 = metricProducer1003->mEventActivationMap.begin()->second;
    EXPECT_EQ(100 * NS_PER_SEC, activation1003.ttl_ns);
    EXPECT_EQ(0, activation1003.activation_ns);
    EXPECT_FALSE(metricProducer1005->isActive());
    const auto& activation1005 = metricProducer1005->mEventActivationMap.begin()->second;
    EXPECT_EQ(100 * NS_PER_SEC, activation1005.ttl_ns);
    EXPECT_EQ(0, activation1005.activation_ns);
    EXPECT_FALSE(metricProducer1006->isActive());
    const auto& activation1006 = metricProducer1006->mEventActivationMap.begin()->second;
    EXPECT_EQ(200 * NS_PER_SEC, activation1006.ttl_ns);
    EXPECT_EQ(0, activation1006.activation_ns);

    processor2->LoadMetricsActivationFromDisk();

    // After loading activations from disk, assert that all 3 metrics are active.
    EXPECT_TRUE(metricProducer1003->isActive());
    EXPECT_EQ(timeBase2 + ttl3 - activation1003.ttl_ns, activation1003.activation_ns);
    EXPECT_TRUE(metricProducer1005->isActive());
    EXPECT_EQ(timeBase2 + ttl5 - activation1005.ttl_ns, activation1005.activation_ns);
    EXPECT_TRUE(metricProducer1006->isActive());
    EXPECT_EQ(timeBase2 + ttl6 - activation1006.ttl_ns, activation1003.activation_ns);

    // Make sure no more broadcasts have happened.
    EXPECT_EQ(broadcastCount, 1);
}

TEST(StatsLogProcessorTest, TestActivationOnBoot) {
    int uid = 1111;

    // Setup a simple config, no activation
    StatsdConfig config1;
    config1.set_id(12341);
    config1.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.
    auto wakelockAcquireMatcher = CreateAcquireWakelockAtomMatcher();
    *config1.add_atom_matcher() = wakelockAcquireMatcher;

    long metricId1 = 1234561;
    long metricId2 = 1234562;
    auto countMetric1 = config1.add_count_metric();
    countMetric1->set_id(metricId1);
    countMetric1->set_what(wakelockAcquireMatcher.id());
    countMetric1->set_bucket(FIVE_MINUTES);

    auto countMetric2 = config1.add_count_metric();
    countMetric2->set_id(metricId2);
    countMetric2->set_what(wakelockAcquireMatcher.id());
    countMetric2->set_bucket(FIVE_MINUTES);

    auto metric1Activation = config1.add_metric_activation();
    metric1Activation->set_metric_id(metricId1);
    metric1Activation->set_activation_type(MetricActivation::ACTIVATE_ON_BOOT);
    auto metric1ActivationTrigger = metric1Activation->add_event_activation();
    metric1ActivationTrigger->set_atom_matcher_id(wakelockAcquireMatcher.id());
    metric1ActivationTrigger->set_ttl_seconds(100);

    ConfigKey cfgKey1(uid, 12341);
    long timeBase1 = 1;
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(timeBase1, timeBase1, config1, cfgKey1);

    EXPECT_EQ(1, processor->mMetricsManagers.size());
    auto it = processor->mMetricsManagers.find(cfgKey1);
    EXPECT_TRUE(it != processor->mMetricsManagers.end());
    auto& metricsManager1 = it->second;
    EXPECT_TRUE(metricsManager1->isActive());

    auto metricIt = metricsManager1->mAllMetricProducers.begin();
    for (; metricIt != metricsManager1->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId1) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager1->mAllMetricProducers.end());
    auto& metricProducer1 = *metricIt;
    EXPECT_FALSE(metricProducer1->isActive());

    metricIt = metricsManager1->mAllMetricProducers.begin();
    for (; metricIt != metricsManager1->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId2) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager1->mAllMetricProducers.end());
    auto& metricProducer2 = *metricIt;
    EXPECT_TRUE(metricProducer2->isActive());

    const auto& activation1 = metricProducer1->mEventActivationMap.begin()->second;
    EXPECT_EQ(100 * NS_PER_SEC, activation1.ttl_ns);
    EXPECT_EQ(0, activation1.activation_ns);
    EXPECT_EQ(kNotActive, activation1.state);

    std::vector<AttributionNodeInternal> attributions1 = {CreateAttribution(111, "App1")};
    auto event = CreateAcquireWakelockEvent(attributions1, "wl1", 100 + timeBase1);
    processor->OnLogEvent(event.get());

    EXPECT_FALSE(metricProducer1->isActive());
    EXPECT_EQ(0, activation1.activation_ns);
    EXPECT_EQ(kActiveOnBoot, activation1.state);

    int64_t shutDownTime = timeBase1 + 100 * NS_PER_SEC;

    processor->WriteMetricsActivationToDisk(shutDownTime);
    EXPECT_TRUE(metricProducer1->isActive());
    int64_t ttl1 = metricProducer1->getRemainingTtlNs(shutDownTime);
    EXPECT_EQ(100 * NS_PER_SEC, ttl1);

    long timeBase2 = 1000;
    sp<StatsLogProcessor> processor2 =
            CreateStatsLogProcessor(timeBase2, timeBase2, config1, cfgKey1);

    EXPECT_EQ(1, processor2->mMetricsManagers.size());
    it = processor2->mMetricsManagers.find(cfgKey1);
    EXPECT_TRUE(it != processor2->mMetricsManagers.end());
    auto& metricsManager1001 = it->second;
    EXPECT_TRUE(metricsManager1001->isActive());

    metricIt = metricsManager1001->mAllMetricProducers.begin();
    for (; metricIt != metricsManager1001->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId1) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager1001->mAllMetricProducers.end());
    auto& metricProducer1001 = *metricIt;
    EXPECT_FALSE(metricProducer1001->isActive());

    metricIt = metricsManager1001->mAllMetricProducers.begin();
    for (; metricIt != metricsManager1001->mAllMetricProducers.end(); metricIt++) {
        if ((*metricIt)->getMetricId() == metricId2) {
            break;
        }
    }
    EXPECT_TRUE(metricIt != metricsManager1001->mAllMetricProducers.end());
    auto& metricProducer1002 = *metricIt;
    EXPECT_TRUE(metricProducer1002->isActive());

    const auto& activation1001 = metricProducer1001->mEventActivationMap.begin()->second;
    EXPECT_EQ(100 * NS_PER_SEC, activation1001.ttl_ns);
    EXPECT_EQ(0, activation1001.activation_ns);
    EXPECT_EQ(kNotActive, activation1001.state);

    processor2->LoadMetricsActivationFromDisk();

    EXPECT_TRUE(metricProducer1001->isActive());
    EXPECT_EQ(timeBase2 + ttl1 - activation1001.ttl_ns, activation1001.activation_ns);
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
