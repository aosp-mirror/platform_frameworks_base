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
                        [](const ConfigKey& key) { return true; });

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
                        });

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
                        });

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
                        });
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
                        });
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
                        });
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

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
