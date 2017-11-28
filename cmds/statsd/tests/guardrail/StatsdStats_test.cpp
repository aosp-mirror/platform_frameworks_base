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

#include <gtest/gtest.h>
#include <vector>

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

using std::vector;

TEST(StatsdStatsTest, TestValidConfigAdd) {
    StatsdStats stats;
    string name = "StatsdTest";
    ConfigKey key(0, name);
    const int metricsCount = 10;
    const int conditionsCount = 20;
    const int matchersCount = 30;
    const int alertsCount = 10;
    stats.noteConfigReceived(key, metricsCount, conditionsCount, matchersCount, alertsCount,
                             true /*valid config*/);
    vector<uint8_t> output;
    stats.dumpStats(&output, false /*reset stats*/);

    StatsdStatsReport report;
    bool good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);
    EXPECT_EQ(1, report.config_stats_size());
    const auto& configReport = report.config_stats(0);
    EXPECT_EQ(0, configReport.uid());
    EXPECT_EQ(name, configReport.name());
    EXPECT_EQ(metricsCount, configReport.metric_count());
    EXPECT_EQ(conditionsCount, configReport.condition_count());
    EXPECT_EQ(matchersCount, configReport.matcher_count());
    EXPECT_EQ(alertsCount, configReport.alert_count());
    EXPECT_EQ(true, configReport.is_valid());
    EXPECT_FALSE(configReport.has_deletion_time_sec());
}

TEST(StatsdStatsTest, TestInvalidConfigAdd) {
    StatsdStats stats;
    string name = "StatsdTest";
    ConfigKey key(0, name);
    const int metricsCount = 10;
    const int conditionsCount = 20;
    const int matchersCount = 30;
    const int alertsCount = 10;
    stats.noteConfigReceived(key, metricsCount, conditionsCount, matchersCount, alertsCount,
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
    string name = "StatsdTest";
    ConfigKey key(0, name);
    const int metricsCount = 10;
    const int conditionsCount = 20;
    const int matchersCount = 30;
    const int alertsCount = 10;
    stats.noteConfigReceived(key, metricsCount, conditionsCount, matchersCount, alertsCount, true);
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
    ConfigKey key(0, "test");
    stats.noteConfigReceived(key, 2, 3, 4, 5, true);

    stats.noteMatcherMatched(key, "matcher1");
    stats.noteMatcherMatched(key, "matcher1");
    stats.noteMatcherMatched(key, "matcher2");

    stats.noteConditionDimensionSize(key, "condition1", 250);
    stats.noteConditionDimensionSize(key, "condition1", 240);

    stats.noteMetricDimensionSize(key, "metric1", 201);
    stats.noteMetricDimensionSize(key, "metric1", 202);

    // broadcast-> 2
    stats.noteBroadcastSent(key);
    stats.noteBroadcastSent(key);

    // data drop -> 1
    stats.noteDataDropped(key);

    // dump report -> 3
    stats.noteMetricsReportSent(key);
    stats.noteMetricsReportSent(key);
    stats.noteMetricsReportSent(key);

    vector<uint8_t> output;
    stats.dumpStats(&output, true);  // Dump and reset stats
    StatsdStatsReport report;
    bool good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);
    EXPECT_EQ(1, report.config_stats_size());
    const auto& configReport = report.config_stats(0);
    EXPECT_EQ(2, configReport.broadcast_sent_time_sec_size());
    EXPECT_EQ(1, configReport.data_drop_time_sec_size());
    EXPECT_EQ(3, configReport.dump_report_time_sec_size());

    EXPECT_EQ(2, configReport.matcher_stats_size());

    // matcher1 is the first in the list
    if (!configReport.matcher_stats(0).name().compare("matcher1")) {
        EXPECT_EQ(2, configReport.matcher_stats(0).matched_times());
        EXPECT_EQ(1, configReport.matcher_stats(1).matched_times());
        EXPECT_EQ("matcher2", configReport.matcher_stats(1).name());
    } else {
        // matcher1 is the second in the list.
        EXPECT_EQ(1, configReport.matcher_stats(0).matched_times());
        EXPECT_EQ("matcher2", configReport.matcher_stats(0).name());

        EXPECT_EQ(2, configReport.matcher_stats(1).matched_times());
        EXPECT_EQ("matcher1", configReport.matcher_stats(1).name());
    }

    EXPECT_EQ(1, configReport.condition_stats_size());
    EXPECT_EQ("condition1", configReport.condition_stats(0).name());
    EXPECT_EQ(250, configReport.condition_stats(0).max_tuple_counts());

    EXPECT_EQ(1, configReport.metric_stats_size());
    EXPECT_EQ("metric1", configReport.metric_stats(0).name());
    EXPECT_EQ(202, configReport.metric_stats(0).max_tuple_counts());

    // after resetting the stats, some new events come
    stats.noteMatcherMatched(key, "matcher99");
    stats.noteConditionDimensionSize(key, "condition99", 300);
    stats.noteMetricDimensionSize(key, "metric99", 270);

    // now the config stats should only contain the stats about the new event.
    stats.dumpStats(&output, false);
    good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);
    EXPECT_EQ(1, report.config_stats_size());
    const auto& configReport2 = report.config_stats(0);
    EXPECT_EQ(1, configReport2.matcher_stats_size());
    EXPECT_EQ("matcher99", configReport2.matcher_stats(0).name());
    EXPECT_EQ(1, configReport2.matcher_stats(0).matched_times());

    EXPECT_EQ(1, configReport2.condition_stats_size());
    EXPECT_EQ("condition99", configReport2.condition_stats(0).name());
    EXPECT_EQ(300, configReport2.condition_stats(0).max_tuple_counts());

    EXPECT_EQ(1, configReport2.metric_stats_size());
    EXPECT_EQ("metric99", configReport2.metric_stats(0).name());
    EXPECT_EQ(270, configReport2.metric_stats(0).max_tuple_counts());
}

TEST(StatsdStatsTest, TestAtomLog) {
    StatsdStats stats;
    time_t now = time(nullptr);
    // old event, we get it from the stats buffer. should be ignored.
    stats.noteAtomLogged(android::util::SENSOR_STATE_CHANGED, 1000);

    stats.noteAtomLogged(android::util::SENSOR_STATE_CHANGED, now + 1);
    stats.noteAtomLogged(android::util::SENSOR_STATE_CHANGED, now + 2);
    stats.noteAtomLogged(android::util::DROPBOX_ERROR_CHANGED, now + 3);
    // pulled event, should ignore
    stats.noteAtomLogged(android::util::WIFI_BYTES_TRANSFERRED, now + 4);

    vector<uint8_t> output;
    stats.dumpStats(&output, false);
    StatsdStatsReport report;
    bool good = report.ParseFromArray(&output[0], output.size());
    EXPECT_TRUE(good);

    EXPECT_EQ(2, report.atom_stats_size());
    bool sensorAtomGood = false;
    bool dropboxAtomGood = false;

    for (const auto& atomStats : report.atom_stats()) {
        if (atomStats.tag() == android::util::SENSOR_STATE_CHANGED && atomStats.count() == 2) {
            sensorAtomGood = true;
        }
        if (atomStats.tag() == android::util::DROPBOX_ERROR_CHANGED && atomStats.count() == 1) {
            dropboxAtomGood = true;
        }
    }

    EXPECT_TRUE(dropboxAtomGood);
    EXPECT_TRUE(sensorAtomGood);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
