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

#include "src/guardrail/StatsdStats.h"
#include "statslog.h"
#include "tests/statsd_test_util.h"

#include <gtest/gtest.h>
#include <vector>

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

using std::vector;

TEST(StatsdStatsTest, TestValidConfigAdd) {
    StatsdStats stats;
    ConfigKey key(0, 12345);
    const int metricsCount = 10;
    const int conditionsCount = 20;
    const int matchersCount = 30;
    const int alertsCount = 10;
    stats.noteConfigReceived(key, metricsCount, conditionsCount, matchersCount, alertsCount, {},
                             true /*valid config*/);
    vector<uint8_t> output;
    stats.dumpStats(&output, false /*reset stats*/);

    StatsdStatsReport report;
    bool good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);
    EXPECT_EQ(1, report.config_stats_size());
    const auto& configReport = report.config_stats(0);
    EXPECT_EQ(0, configReport.uid());
    EXPECT_EQ(12345, configReport.id());
    EXPECT_EQ(metricsCount, configReport.metric_count());
    EXPECT_EQ(conditionsCount, configReport.condition_count());
    EXPECT_EQ(matchersCount, configReport.matcher_count());
    EXPECT_EQ(alertsCount, configReport.alert_count());
    EXPECT_EQ(true, configReport.is_valid());
    EXPECT_FALSE(configReport.has_deletion_time_sec());
}

TEST(StatsdStatsTest, TestInvalidConfigAdd) {
    StatsdStats stats;
    ConfigKey key(0, 12345);
    const int metricsCount = 10;
    const int conditionsCount = 20;
    const int matchersCount = 30;
    const int alertsCount = 10;
    stats.noteConfigReceived(key, metricsCount, conditionsCount, matchersCount, alertsCount, {},
                             false /*bad config*/);
    vector<uint8_t> output;
    stats.dumpStats(&output, false);

    StatsdStatsReport report;
    bool good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);
    EXPECT_EQ(1, report.config_stats_size());
    const auto& configReport = report.config_stats(0);
    // The invalid config should be put into icebox with a deletion time.
    EXPECT_TRUE(configReport.has_deletion_time_sec());
}

TEST(StatsdStatsTest, TestConfigRemove) {
    StatsdStats stats;
    ConfigKey key(0, 12345);
    const int metricsCount = 10;
    const int conditionsCount = 20;
    const int matchersCount = 30;
    const int alertsCount = 10;
    stats.noteConfigReceived(key, metricsCount, conditionsCount, matchersCount, alertsCount, {},
                             true);
    vector<uint8_t> output;
    stats.dumpStats(&output, false);
    StatsdStatsReport report;
    bool good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);
    EXPECT_EQ(1, report.config_stats_size());
    const auto& configReport = report.config_stats(0);
    EXPECT_FALSE(configReport.has_deletion_time_sec());

    stats.noteConfigRemoved(key);
    stats.dumpStats(&output, false);
    good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);
    EXPECT_EQ(1, report.config_stats_size());
    const auto& configReport2 = report.config_stats(0);
    EXPECT_TRUE(configReport2.has_deletion_time_sec());
}

TEST(StatsdStatsTest, TestSubStats) {
    StatsdStats stats;
    ConfigKey key(0, 12345);
    stats.noteConfigReceived(key, 2, 3, 4, 5, {std::make_pair(123, 456)}, true);

    stats.noteMatcherMatched(key, StringToId("matcher1"));
    stats.noteMatcherMatched(key, StringToId("matcher1"));
    stats.noteMatcherMatched(key, StringToId("matcher2"));

    stats.noteConditionDimensionSize(key, StringToId("condition1"), 250);
    stats.noteConditionDimensionSize(key, StringToId("condition1"), 240);

    stats.noteMetricDimensionSize(key, StringToId("metric1"), 201);
    stats.noteMetricDimensionSize(key, StringToId("metric1"), 202);

    stats.noteAnomalyDeclared(key, StringToId("alert1"));
    stats.noteAnomalyDeclared(key, StringToId("alert1"));
    stats.noteAnomalyDeclared(key, StringToId("alert2"));

    // broadcast-> 2
    stats.noteBroadcastSent(key);
    stats.noteBroadcastSent(key);

    // data drop -> 1
    stats.noteDataDropped(key, 123);

    // dump report -> 3
    stats.noteMetricsReportSent(key, 0);
    stats.noteMetricsReportSent(key, 0);
    stats.noteMetricsReportSent(key, 0);

    vector<uint8_t> output;
    stats.dumpStats(&output, true);  // Dump and reset stats
    StatsdStatsReport report;
    bool good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);
    EXPECT_EQ(1, report.config_stats_size());
    const auto& configReport = report.config_stats(0);
    EXPECT_EQ(2, configReport.broadcast_sent_time_sec_size());
    EXPECT_EQ(1, configReport.data_drop_time_sec_size());
    EXPECT_EQ(1, configReport.data_drop_bytes_size());
    EXPECT_EQ(123, configReport.data_drop_bytes(0));
    EXPECT_EQ(3, configReport.dump_report_time_sec_size());
    EXPECT_EQ(3, configReport.dump_report_data_size_size());
    EXPECT_EQ(1, configReport.annotation_size());
    EXPECT_EQ(123, configReport.annotation(0).field_int64());
    EXPECT_EQ(456, configReport.annotation(0).field_int32());

    EXPECT_EQ(2, configReport.matcher_stats_size());
    // matcher1 is the first in the list
    if (configReport.matcher_stats(0).id() == StringToId("matcher1")) {
        EXPECT_EQ(2, configReport.matcher_stats(0).matched_times());
        EXPECT_EQ(1, configReport.matcher_stats(1).matched_times());
        EXPECT_EQ(StringToId("matcher2"), configReport.matcher_stats(1).id());
    } else {
        // matcher1 is the second in the list.
        EXPECT_EQ(1, configReport.matcher_stats(0).matched_times());
        EXPECT_EQ(StringToId("matcher2"), configReport.matcher_stats(0).id());

        EXPECT_EQ(2, configReport.matcher_stats(1).matched_times());
        EXPECT_EQ(StringToId("matcher1"), configReport.matcher_stats(1).id());
    }

    EXPECT_EQ(2, configReport.alert_stats_size());
    bool alert1first = configReport.alert_stats(0).id() == StringToId("alert1");
    EXPECT_EQ(StringToId("alert1"), configReport.alert_stats(alert1first ? 0 : 1).id());
    EXPECT_EQ(2, configReport.alert_stats(alert1first ? 0 : 1).alerted_times());
    EXPECT_EQ(StringToId("alert2"), configReport.alert_stats(alert1first ? 1 : 0).id());
    EXPECT_EQ(1, configReport.alert_stats(alert1first ? 1 : 0).alerted_times());

    EXPECT_EQ(1, configReport.condition_stats_size());
    EXPECT_EQ(StringToId("condition1"), configReport.condition_stats(0).id());
    EXPECT_EQ(250, configReport.condition_stats(0).max_tuple_counts());

    EXPECT_EQ(1, configReport.metric_stats_size());
    EXPECT_EQ(StringToId("metric1"), configReport.metric_stats(0).id());
    EXPECT_EQ(202, configReport.metric_stats(0).max_tuple_counts());

    // after resetting the stats, some new events come
    stats.noteMatcherMatched(key, StringToId("matcher99"));
    stats.noteConditionDimensionSize(key, StringToId("condition99"), 300);
    stats.noteMetricDimensionSize(key, StringToId("metric99tion99"), 270);
    stats.noteAnomalyDeclared(key, StringToId("alert99"));

    // now the config stats should only contain the stats about the new event.
    stats.dumpStats(&output, false);
    good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);
    EXPECT_EQ(1, report.config_stats_size());
    const auto& configReport2 = report.config_stats(0);
    EXPECT_EQ(1, configReport2.matcher_stats_size());
    EXPECT_EQ(StringToId("matcher99"), configReport2.matcher_stats(0).id());
    EXPECT_EQ(1, configReport2.matcher_stats(0).matched_times());

    EXPECT_EQ(1, configReport2.condition_stats_size());
    EXPECT_EQ(StringToId("condition99"), configReport2.condition_stats(0).id());
    EXPECT_EQ(300, configReport2.condition_stats(0).max_tuple_counts());

    EXPECT_EQ(1, configReport2.metric_stats_size());
    EXPECT_EQ(StringToId("metric99tion99"), configReport2.metric_stats(0).id());
    EXPECT_EQ(270, configReport2.metric_stats(0).max_tuple_counts());

    EXPECT_EQ(1, configReport2.alert_stats_size());
    EXPECT_EQ(StringToId("alert99"), configReport2.alert_stats(0).id());
    EXPECT_EQ(1, configReport2.alert_stats(0).alerted_times());
}

TEST(StatsdStatsTest, TestAtomLog) {
    StatsdStats stats;
    time_t now = time(nullptr);
    // old event, we get it from the stats buffer. should be ignored.
    stats.noteAtomLogged(android::util::SENSOR_STATE_CHANGED, 1000);

    stats.noteAtomLogged(android::util::SENSOR_STATE_CHANGED, now + 1);
    stats.noteAtomLogged(android::util::SENSOR_STATE_CHANGED, now + 2);
    stats.noteAtomLogged(android::util::APP_CRASH_OCCURRED, now + 3);
    // pulled event, should ignore
    stats.noteAtomLogged(android::util::WIFI_BYTES_TRANSFER, now + 4);

    vector<uint8_t> output;
    stats.dumpStats(&output, false);
    StatsdStatsReport report;
    bool good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);

    EXPECT_EQ(2, report.atom_stats_size());
    bool sensorAtomGood = false;
    bool dropboxAtomGood = false;

    for (const auto& atomStats : report.atom_stats()) {
        if (atomStats.tag() == android::util::SENSOR_STATE_CHANGED && atomStats.count() == 3) {
            sensorAtomGood = true;
        }
        if (atomStats.tag() == android::util::APP_CRASH_OCCURRED && atomStats.count() == 1) {
            dropboxAtomGood = true;
        }
    }

    EXPECT_TRUE(dropboxAtomGood);
    EXPECT_TRUE(sensorAtomGood);
}

TEST(StatsdStatsTest, TestPullAtomStats) {
    StatsdStats stats;

    stats.updateMinPullIntervalSec(android::util::DISK_SPACE, 3333L);
    stats.updateMinPullIntervalSec(android::util::DISK_SPACE, 2222L);
    stats.updateMinPullIntervalSec(android::util::DISK_SPACE, 4444L);

    stats.notePull(android::util::DISK_SPACE);
    stats.notePullTime(android::util::DISK_SPACE, 1111L);
    stats.notePullDelay(android::util::DISK_SPACE, 1111L);
    stats.notePull(android::util::DISK_SPACE);
    stats.notePullTime(android::util::DISK_SPACE, 3333L);
    stats.notePullDelay(android::util::DISK_SPACE, 3335L);
    stats.notePull(android::util::DISK_SPACE);
    stats.notePullFromCache(android::util::DISK_SPACE);

    vector<uint8_t> output;
    stats.dumpStats(&output, false);
    StatsdStatsReport report;
    bool good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);

    EXPECT_EQ(1, report.pulled_atom_stats_size());

    EXPECT_EQ(android::util::DISK_SPACE, report.pulled_atom_stats(0).atom_id());
    EXPECT_EQ(3, report.pulled_atom_stats(0).total_pull());
    EXPECT_EQ(1, report.pulled_atom_stats(0).total_pull_from_cache());
    EXPECT_EQ(2222L, report.pulled_atom_stats(0).min_pull_interval_sec());
    EXPECT_EQ(2222L, report.pulled_atom_stats(0).average_pull_time_nanos());
    EXPECT_EQ(3333L, report.pulled_atom_stats(0).max_pull_time_nanos());
    EXPECT_EQ(2223L, report.pulled_atom_stats(0).average_pull_delay_nanos());
    EXPECT_EQ(3335L, report.pulled_atom_stats(0).max_pull_delay_nanos());
}

TEST(StatsdStatsTest, TestAtomMetricsStats) {
    StatsdStats stats;
    time_t now = time(nullptr);
    // old event, we get it from the stats buffer. should be ignored.
    stats.noteBucketDropped(1000L);

    stats.noteBucketBoundaryDelayNs(1000L, -1L);
    stats.noteBucketBoundaryDelayNs(1000L, -10L);
    stats.noteBucketBoundaryDelayNs(1000L, 2L);

    stats.noteBucketBoundaryDelayNs(1001L, 1L);

    vector<uint8_t> output;
    stats.dumpStats(&output, false);
    StatsdStatsReport report;
    bool good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);

    EXPECT_EQ(2, report.atom_metric_stats().size());

    auto atomStats = report.atom_metric_stats(0);
    EXPECT_EQ(1000L, atomStats.metric_id());
    EXPECT_EQ(1L, atomStats.bucket_dropped());
    EXPECT_EQ(-10L, atomStats.min_bucket_boundary_delay_ns());
    EXPECT_EQ(2L, atomStats.max_bucket_boundary_delay_ns());

    auto atomStats2 = report.atom_metric_stats(1);
    EXPECT_EQ(1001L, atomStats2.metric_id());
    EXPECT_EQ(0L, atomStats2.bucket_dropped());
    EXPECT_EQ(0L, atomStats2.min_bucket_boundary_delay_ns());
    EXPECT_EQ(1L, atomStats2.max_bucket_boundary_delay_ns());
}

TEST(StatsdStatsTest, TestAnomalyMonitor) {
    StatsdStats stats;
    stats.noteRegisteredAnomalyAlarmChanged();
    stats.noteRegisteredAnomalyAlarmChanged();

    vector<uint8_t> output;
    stats.dumpStats(&output, false);
    StatsdStatsReport report;
    bool good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);

    EXPECT_EQ(2, report.anomaly_alarm_stats().alarms_registered());
}

TEST(StatsdStatsTest, TestTimestampThreshold) {
    StatsdStats stats;
    vector<int32_t> timestamps;
    for (int i = 0; i < StatsdStats::kMaxTimestampCount; i++) {
        timestamps.push_back(i);
    }
    ConfigKey key(0, 12345);
    stats.noteConfigReceived(key, 2, 3, 4, 5, {}, true);

    for (int i = 0; i < StatsdStats::kMaxTimestampCount; i++) {
        stats.noteDataDropped(key, timestamps[i]);
        stats.noteBroadcastSent(key, timestamps[i]);
        stats.noteMetricsReportSent(key, 0, timestamps[i]);
    }

    int32_t newTimestamp = 10000;

    // now it should trigger removing oldest timestamp
    stats.noteDataDropped(key, 123, 10000);
    stats.noteBroadcastSent(key, 10000);
    stats.noteMetricsReportSent(key, 0, 10000);

    EXPECT_TRUE(stats.mConfigStats.find(key) != stats.mConfigStats.end());
    const auto& configStats = stats.mConfigStats[key];

    size_t maxCount = StatsdStats::kMaxTimestampCount;
    EXPECT_EQ(maxCount, configStats->broadcast_sent_time_sec.size());
    EXPECT_EQ(maxCount, configStats->data_drop_time_sec.size());
    EXPECT_EQ(maxCount, configStats->dump_report_stats.size());

    // the oldest timestamp is the second timestamp in history
    EXPECT_EQ(1, configStats->broadcast_sent_time_sec.front());
    EXPECT_EQ(1, configStats->broadcast_sent_time_sec.front());
    EXPECT_EQ(1, configStats->broadcast_sent_time_sec.front());

    // the last timestamp is the newest timestamp.
    EXPECT_EQ(newTimestamp, configStats->broadcast_sent_time_sec.back());
    EXPECT_EQ(newTimestamp, configStats->data_drop_time_sec.back());
    EXPECT_EQ(123, configStats->data_drop_bytes.back());
    EXPECT_EQ(newTimestamp, configStats->dump_report_stats.back().first);
}

TEST(StatsdStatsTest, TestSystemServerCrash) {
    StatsdStats stats;
    vector<int32_t> timestamps;
    for (int i = 0; i < StatsdStats::kMaxSystemServerRestarts; i++) {
        timestamps.push_back(i);
        stats.noteSystemServerRestart(timestamps[i]);
    }
    vector<uint8_t> output;
    stats.dumpStats(&output, false);
    StatsdStatsReport report;
    EXPECT_TRUE(report.ParseFromArray(&output[0], output.size()));
    const int maxCount = StatsdStats::kMaxSystemServerRestarts;
    EXPECT_EQ(maxCount, (int)report.system_restart_sec_size());

    stats.noteSystemServerRestart(StatsdStats::kMaxSystemServerRestarts + 1);
    output.clear();
    stats.dumpStats(&output, false);
    EXPECT_TRUE(report.ParseFromArray(&output[0], output.size()));
    EXPECT_EQ(maxCount, (int)report.system_restart_sec_size());
    EXPECT_EQ(StatsdStats::kMaxSystemServerRestarts + 1, report.system_restart_sec(maxCount - 1));
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
