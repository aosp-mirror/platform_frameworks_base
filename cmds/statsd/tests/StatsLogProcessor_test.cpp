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
    MockMetricsManager() : MetricsManager(
        ConfigKey(1, 12345), StatsdConfig(), 1000, 1000,
        new UidMap(),
        new AlarmMonitor(10, [](const sp<IStatsCompanionService>&, int64_t){},
                         [](const sp<IStatsCompanionService>&){}),
        new AlarmMonitor(10, [](const sp<IStatsCompanionService>&, int64_t){},
                         [](const sp<IStatsCompanionService>&){})) {
    }

    MOCK_METHOD0(byteSize, size_t());

    MOCK_METHOD1(dropData, void(const int64_t dropTimeNs));
};

TEST(StatsLogProcessorTest, TestRateLimitByteSize) {
    sp<UidMap> m = new UidMap();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> periodicAlarmMonitor;
    // Construct the processor with a dummy sendBroadcast function that does nothing.
    StatsLogProcessor p(m, anomalyAlarmMonitor, periodicAlarmMonitor, 0,
        [](const ConfigKey& key) {return true;});

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
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    int broadcastCount = 0;
    StatsLogProcessor p(m, anomalyAlarmMonitor, subscriberAlarmMonitor, 0,
                        [&broadcastCount](const ConfigKey& key) { broadcastCount++; return true;});

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
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    int broadcastCount = 0;
    StatsLogProcessor p(m, anomalyAlarmMonitor, subscriberAlarmMonitor, 0,
                        [&broadcastCount](const ConfigKey& key) { broadcastCount++; return true;});

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

TEST(StatsLogProcessorTest, TestUidMapHasSnapshot) {
    // Setup simple config key corresponding to empty config.
    sp<UidMap> m = new UidMap();
    m->updateMap(1, {1, 2}, {1, 2}, {String16("p1"), String16("p2")});
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    int broadcastCount = 0;
    StatsLogProcessor p(m, anomalyAlarmMonitor, subscriberAlarmMonitor, 0,
                        [&broadcastCount](const ConfigKey& key) { broadcastCount++; return true;});
    ConfigKey key(3, 4);
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");
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

TEST(StatsLogProcessorTest, TestReportIncludesSubConfig) {
    // Setup simple config key corresponding to empty config.
    sp<UidMap> m = new UidMap();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    int broadcastCount = 0;
    StatsLogProcessor p(m, anomalyAlarmMonitor, subscriberAlarmMonitor, 0,
                        [&broadcastCount](const ConfigKey& key) { broadcastCount++; return true;});
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

TEST(StatsLogProcessorTest, TestOutOfOrderLogs) {
    // Setup simple config key corresponding to empty config.
    sp<UidMap> m = new UidMap();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    int broadcastCount = 0;
    StatsLogProcessor p(m, anomalyAlarmMonitor, subscriberAlarmMonitor, 0,
                        [&broadcastCount](const ConfigKey& key) { broadcastCount++; return true;});

    LogEvent event1(0, 1 /*logd timestamp*/, 1001 /*elapsedRealtime*/);
    event1.init();

    LogEvent event2(0, 2, 1002);
    event2.init();

    LogEvent event3(0, 3, 1005);
    event3.init();

    LogEvent event4(0, 4, 1004);
    event4.init();

    // <----- Reconnection happens

    LogEvent event5(0, 5, 999);
    event5.init();

    LogEvent event6(0, 6, 2000);
    event6.init();

    // <----- Reconnection happens

    LogEvent event7(0, 7, 3000);
    event7.init();

    // first event ever
    p.OnLogEvent(&event1, true);
    EXPECT_EQ(1UL, p.mLogCount);
    EXPECT_EQ(1001LL, p.mLargestTimestampSeen);
    EXPECT_EQ(1001LL, p.mLastTimestampSeen);

    p.OnLogEvent(&event2, false);
    EXPECT_EQ(2UL, p.mLogCount);
    EXPECT_EQ(1002LL, p.mLargestTimestampSeen);
    EXPECT_EQ(1002LL, p.mLastTimestampSeen);

    p.OnLogEvent(&event3, false);
    EXPECT_EQ(3UL, p.mLogCount);
    EXPECT_EQ(1005LL, p.mLargestTimestampSeen);
    EXPECT_EQ(1005LL, p.mLastTimestampSeen);

    p.OnLogEvent(&event4, false);
    EXPECT_EQ(4UL, p.mLogCount);
    EXPECT_EQ(1005LL, p.mLargestTimestampSeen);
    EXPECT_EQ(1004LL, p.mLastTimestampSeen);
    EXPECT_FALSE(p.mInReconnection);

    // Reconnect happens, event1 out of buffer. Read event2
    p.OnLogEvent(&event2, true);
    EXPECT_EQ(4UL, p.mLogCount);
    EXPECT_EQ(1005LL, p.mLargestTimestampSeen);
    EXPECT_EQ(1004LL, p.mLastTimestampSeen);
    EXPECT_TRUE(p.mInReconnection);

    p.OnLogEvent(&event3, false);
    EXPECT_EQ(4UL, p.mLogCount);
    EXPECT_EQ(1005LL, p.mLargestTimestampSeen);
    EXPECT_EQ(1004LL, p.mLastTimestampSeen);
    EXPECT_TRUE(p.mInReconnection);

    p.OnLogEvent(&event4, false);
    EXPECT_EQ(4UL, p.mLogCount);
    EXPECT_EQ(1005LL, p.mLargestTimestampSeen);
    EXPECT_EQ(1004LL, p.mLastTimestampSeen);
    EXPECT_FALSE(p.mInReconnection);

    // Fresh event comes.
    p.OnLogEvent(&event5, false);
    EXPECT_EQ(5UL, p.mLogCount);
    EXPECT_EQ(1005LL, p.mLargestTimestampSeen);
    EXPECT_EQ(999LL, p.mLastTimestampSeen);

    p.OnLogEvent(&event6, false);
    EXPECT_EQ(6UL, p.mLogCount);
    EXPECT_EQ(2000LL, p.mLargestTimestampSeen);
    EXPECT_EQ(2000LL, p.mLastTimestampSeen);

    // Reconnect happens, read from event4
    p.OnLogEvent(&event4, true);
    EXPECT_EQ(6UL, p.mLogCount);
    EXPECT_EQ(2000LL, p.mLargestTimestampSeen);
    EXPECT_EQ(2000LL, p.mLastTimestampSeen);
    EXPECT_TRUE(p.mInReconnection);

    p.OnLogEvent(&event5, false);
    EXPECT_EQ(6UL, p.mLogCount);
    EXPECT_EQ(2000LL, p.mLargestTimestampSeen);
    EXPECT_EQ(2000LL, p.mLastTimestampSeen);
    EXPECT_TRUE(p.mInReconnection);

    // Before we get out of reconnection state, it reconnects again.
    p.OnLogEvent(&event5, true);
    EXPECT_EQ(6UL, p.mLogCount);
    EXPECT_EQ(2000LL, p.mLargestTimestampSeen);
    EXPECT_EQ(2000LL, p.mLastTimestampSeen);
    EXPECT_TRUE(p.mInReconnection);

    p.OnLogEvent(&event6, false);
    EXPECT_EQ(6UL, p.mLogCount);
    EXPECT_EQ(2000LL, p.mLargestTimestampSeen);
    EXPECT_EQ(2000LL, p.mLastTimestampSeen);
    EXPECT_FALSE(p.mInReconnection);
    EXPECT_EQ(0, p.mLogLossCount);

    // it reconnects again. All old events are gone. We lose CP.
    p.OnLogEvent(&event7, true);
    EXPECT_EQ(7UL, p.mLogCount);
    EXPECT_EQ(3000LL, p.mLargestTimestampSeen);
    EXPECT_EQ(3000LL, p.mLastTimestampSeen);
    EXPECT_EQ(1, p.mLogLossCount);
    EXPECT_FALSE(p.mInReconnection);
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
