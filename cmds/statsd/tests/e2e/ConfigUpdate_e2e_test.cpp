// Copyright (C) 2020 The Android Open Source Project
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

#include <gtest/gtest.h>

#include <thread>  // std::this_thread::sleep_for

#include "android-base/stringprintf.h"
#include "src/StatsLogProcessor.h"
#include "src/storage/StorageManager.h"
#include "tests/statsd_test_util.h"

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__
#define STATS_DATA_DIR "/data/misc/stats-data"
using android::base::StringPrintf;

namespace {

StatsdConfig CreateSimpleConfig() {
    StatsdConfig config;
    config.add_allowed_log_source("AID_STATSD");
    config.set_hash_strings_in_metric_report(false);

    *config.add_atom_matcher() = CreateBatteryStateUsbMatcher();
    // Simple count metric so the config isn't empty.
    CountMetric* countMetric1 = config.add_count_metric();
    countMetric1->set_id(StringToId("Count1"));
    countMetric1->set_what(config.atom_matcher(0).id());
    countMetric1->set_bucket(FIVE_MINUTES);
    return config;
}
}  // namespace

// Setup for parameterized tests.
class ConfigUpdateE2eTest : public TestWithParam<bool> {};

INSTANTIATE_TEST_SUITE_P(ConfigUpdateE2eTest, ConfigUpdateE2eTest, testing::Bool());

TEST_P(ConfigUpdateE2eTest, TestUidMapVersionStringInstaller) {
    sp<UidMap> uidMap = new UidMap();
    vector<int32_t> uids({1000});
    vector<int64_t> versions({1});
    vector<String16> apps({String16("app1")});
    vector<String16> versionStrings({String16("v1")});
    vector<String16> installers({String16("installer1")});
    uidMap->updateMap(1, uids, versions, versionStrings, apps, installers);

    StatsdConfig config = CreateSimpleConfig();
    config.set_version_strings_in_metric_report(true);
    config.set_installer_in_metric_report(false);
    int64_t baseTimeNs = getElapsedRealtimeNs();

    ConfigKey cfgKey(0, 12345);
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(baseTimeNs, baseTimeNs, config, cfgKey, nullptr, 0, uidMap);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());

    // Now update.
    config.set_version_strings_in_metric_report(false);
    config.set_installer_in_metric_report(true);
    processor->OnConfigUpdated(baseTimeNs + 1000, cfgKey, config, /*modularUpdate=*/GetParam());
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_EQ(metricsManager == processor->mMetricsManagers.begin()->second, GetParam());
    EXPECT_TRUE(metricsManager->isConfigValid());

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, baseTimeNs + 1001, false, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    // First report is written to disk when the update happens.
    ASSERT_EQ(reports.reports_size(), 2);
    UidMapping uidMapping = reports.reports(1).uid_map();
    ASSERT_EQ(uidMapping.snapshots_size(), 1);
    ASSERT_EQ(uidMapping.snapshots(0).package_info_size(), 1);
    EXPECT_FALSE(uidMapping.snapshots(0).package_info(0).has_version_string());
    EXPECT_EQ(uidMapping.snapshots(0).package_info(0).installer(), "installer1");
}

TEST_P(ConfigUpdateE2eTest, TestHashStrings) {
    sp<UidMap> uidMap = new UidMap();
    vector<int32_t> uids({1000});
    vector<int64_t> versions({1});
    vector<String16> apps({String16("app1")});
    vector<String16> versionStrings({String16("v1")});
    vector<String16> installers({String16("installer1")});
    uidMap->updateMap(1, uids, versions, versionStrings, apps, installers);

    StatsdConfig config = CreateSimpleConfig();
    config.set_version_strings_in_metric_report(true);
    config.set_hash_strings_in_metric_report(true);
    int64_t baseTimeNs = getElapsedRealtimeNs();

    ConfigKey cfgKey(0, 12345);
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(baseTimeNs, baseTimeNs, config, cfgKey, nullptr, 0, uidMap);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_TRUE(metricsManager->isConfigValid());

    // Now update.
    config.set_hash_strings_in_metric_report(false);
    processor->OnConfigUpdated(baseTimeNs + 1000, cfgKey, config, /*modularUpdate=*/GetParam());
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_EQ(metricsManager == processor->mMetricsManagers.begin()->second, GetParam());
    EXPECT_TRUE(metricsManager->isConfigValid());

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, baseTimeNs + 1001, false, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    // First report is written to disk when the update happens.
    ASSERT_EQ(reports.reports_size(), 2);
    UidMapping uidMapping = reports.reports(1).uid_map();
    ASSERT_EQ(uidMapping.snapshots_size(), 1);
    ASSERT_EQ(uidMapping.snapshots(0).package_info_size(), 1);
    EXPECT_TRUE(uidMapping.snapshots(0).package_info(0).has_version_string());
    EXPECT_FALSE(uidMapping.snapshots(0).package_info(0).has_version_string_hash());
}

TEST_P(ConfigUpdateE2eTest, TestAnnotations) {
    StatsdConfig config = CreateSimpleConfig();
    StatsdConfig_Annotation* annotation = config.add_annotation();
    annotation->set_field_int64(11);
    annotation->set_field_int32(1);
    int64_t baseTimeNs = getElapsedRealtimeNs();
    ConfigKey cfgKey(0, 12345);
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(baseTimeNs, baseTimeNs, config, cfgKey);

    // Now update
    config.clear_annotation();
    annotation = config.add_annotation();
    annotation->set_field_int64(22);
    annotation->set_field_int32(2);
    processor->OnConfigUpdated(baseTimeNs + 1000, cfgKey, config, /*modularUpdate=*/GetParam());

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, baseTimeNs + 1001, false, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    // First report is written to disk when the update happens.
    ASSERT_EQ(reports.reports_size(), 2);
    ConfigMetricsReport report = reports.reports(1);
    EXPECT_EQ(report.annotation_size(), 1);
    EXPECT_EQ(report.annotation(0).field_int64(), 22);
    EXPECT_EQ(report.annotation(0).field_int32(), 2);
}

TEST_P(ConfigUpdateE2eTest, TestPersistLocally) {
    StatsdConfig config = CreateSimpleConfig();
    config.set_persist_locally(false);
    int64_t baseTimeNs = getElapsedRealtimeNs();
    ConfigKey cfgKey(0, 12345);
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(baseTimeNs, baseTimeNs, config, cfgKey);
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, baseTimeNs + 1001, false, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    ASSERT_EQ(reports.reports_size(), 1);
    // Number of reports should still be 1 since persist_locally is false.
    reports.Clear();
    buffer.clear();
    processor->onDumpReport(cfgKey, baseTimeNs + 1001, false, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    ASSERT_EQ(reports.reports_size(), 1);

    // Now update.
    config.set_persist_locally(true);
    processor->OnConfigUpdated(baseTimeNs + 1000, cfgKey, config, /*modularUpdate=*/GetParam());

    // Should get 2: 1 in memory + 1 on disk. Both should be saved on disk.
    reports.Clear();
    buffer.clear();
    processor->onDumpReport(cfgKey, baseTimeNs + 1001, false, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    ASSERT_EQ(reports.reports_size(), 2);
    // Should get 3, 2 on disk + 1 in memory.
    reports.Clear();
    buffer.clear();
    processor->onDumpReport(cfgKey, baseTimeNs + 1001, false, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    ASSERT_EQ(reports.reports_size(), 3);
    string suffix = StringPrintf("%d_%lld", cfgKey.GetUid(), (long long)cfgKey.GetId());
    StorageManager::deleteSuffixedFiles(STATS_DATA_DIR, suffix.c_str());
    string historySuffix =
            StringPrintf("%d_%lld_history", cfgKey.GetUid(), (long long)cfgKey.GetId());
    StorageManager::deleteSuffixedFiles(STATS_DATA_DIR, historySuffix.c_str());
}

TEST_P(ConfigUpdateE2eTest, TestNoReportMetrics) {
    StatsdConfig config = CreateSimpleConfig();
    // Second simple count metric.
    CountMetric* countMetric = config.add_count_metric();
    countMetric->set_id(StringToId("Count2"));
    countMetric->set_what(config.atom_matcher(0).id());
    countMetric->set_bucket(FIVE_MINUTES);
    config.add_no_report_metric(config.count_metric(0).id());
    int64_t baseTimeNs = getElapsedRealtimeNs();
    ConfigKey cfgKey(0, 12345);
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(baseTimeNs, baseTimeNs, config, cfgKey);

    // Now update.
    config.clear_no_report_metric();
    config.add_no_report_metric(config.count_metric(1).id());
    processor->OnConfigUpdated(baseTimeNs + 1000, cfgKey, config, /*modularUpdate=*/GetParam());

    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, baseTimeNs + 1001, false, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    // First report is written to disk when the update happens.
    ASSERT_EQ(reports.reports_size(), 2);
    // First report (before update) has the first count metric.
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_EQ(reports.reports(0).metrics(0).metric_id(), config.count_metric(1).id());
    // Second report (after update) has the first count metric.
    ASSERT_EQ(reports.reports(1).metrics_size(), 1);
    EXPECT_EQ(reports.reports(1).metrics(0).metric_id(), config.count_metric(0).id());
}

TEST_P(ConfigUpdateE2eTest, TestAtomsAllowedFromAnyUid) {
    StatsdConfig config = CreateSimpleConfig();
    int64_t baseTimeNs = getElapsedRealtimeNs();
    ConfigKey cfgKey(0, 12345);
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(baseTimeNs, baseTimeNs, config, cfgKey);
    // Uses AID_ROOT, which isn't in allowed log sources.
    unique_ptr<LogEvent> event = CreateBatteryStateChangedEvent(
            baseTimeNs + 2, BatteryPluggedStateEnum::BATTERY_PLUGGED_USB);
    processor->OnLogEvent(event.get());
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, baseTimeNs + 1001, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    ASSERT_EQ(reports.reports_size(), 1);
    // Check the metric and make sure it has 0 count.
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_FALSE(reports.reports(0).metrics(0).has_count_metrics());

    // Now update. Allow plugged state to be logged from any uid, so the atom will be counted.
    config.add_whitelisted_atom_ids(util::PLUGGED_STATE_CHANGED);
    processor->OnConfigUpdated(baseTimeNs + 1000, cfgKey, config, /*modularUpdate=*/GetParam());
    unique_ptr<LogEvent> event2 = CreateBatteryStateChangedEvent(
            baseTimeNs + 2000, BatteryPluggedStateEnum::BATTERY_PLUGGED_USB);
    processor->OnLogEvent(event.get());
    reports.Clear();
    buffer.clear();
    processor->onDumpReport(cfgKey, baseTimeNs + 3000, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    ASSERT_EQ(reports.reports_size(), 2);
    // Check the metric and make sure it has 0 count.
    ASSERT_EQ(reports.reports(1).metrics_size(), 1);
    EXPECT_TRUE(reports.reports(1).metrics(0).has_count_metrics());
    ASSERT_EQ(reports.reports(1).metrics(0).count_metrics().data_size(), 1);
    ASSERT_EQ(reports.reports(1).metrics(0).count_metrics().data(0).bucket_info_size(), 1);
    EXPECT_EQ(reports.reports(1).metrics(0).count_metrics().data(0).bucket_info(0).count(), 1);
}

TEST_P(ConfigUpdateE2eTest, TestConfigTtl) {
    StatsdConfig config = CreateSimpleConfig();
    config.set_ttl_in_seconds(1);
    int64_t baseTimeNs = getElapsedRealtimeNs();
    ConfigKey cfgKey(0, 12345);
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(baseTimeNs, baseTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    sp<MetricsManager> metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_EQ(metricsManager->getTtlEndNs(), baseTimeNs + NS_PER_SEC);

    config.set_ttl_in_seconds(5);
    processor->OnConfigUpdated(baseTimeNs + 2 * NS_PER_SEC, cfgKey, config,
                               /*modularUpdate=*/GetParam());
    metricsManager = processor->mMetricsManagers.begin()->second;
    EXPECT_EQ(metricsManager->getTtlEndNs(), baseTimeNs + 7 * NS_PER_SEC);

    // Clear the data stored on disk as a result of the update.
    vector<uint8_t> buffer;
    processor->onDumpReport(cfgKey, baseTimeNs + 3 * NS_PER_SEC, false, true, ADB_DUMP, FAST,
                            &buffer);
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
