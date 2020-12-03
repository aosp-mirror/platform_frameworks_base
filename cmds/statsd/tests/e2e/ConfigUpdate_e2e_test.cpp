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
using namespace std;

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

TEST(ConfigUpdateE2eTest, TestNewDurationExistingWhat) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();

    Predicate holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    *config.add_predicate() = holdingWakelockPredicate;

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    uint64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(FIVE_MINUTES) * 1000000LL;
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    int app1Uid = 123;
    vector<int> attributionUids1 = {app1Uid};
    vector<string> attributionTags1 = {"App1"};
    // Create a wakelock acquire, causing the condition to be true.
    unique_ptr<LogEvent> event = CreateAcquireWakelockEvent(bucketStartTimeNs + 10 * NS_PER_SEC,
                                                            attributionUids1, attributionTags1,
                                                            "wl1");  // 0:10
    processor->OnLogEvent(event.get());

    // Add metric.
    DurationMetric* durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->set_aggregation_type(DurationMetric::SUM);
    durationMetric->set_bucket(FIVE_MINUTES);

    uint64_t updateTimeNs = bucketStartTimeNs + 60 * NS_PER_SEC;  // 1:00
    processor->OnConfigUpdated(updateTimeNs, key, config, /*modular*/ true);

    event = CreateReleaseWakelockEvent(bucketStartTimeNs + 80 * NS_PER_SEC, attributionUids1,
                                       attributionTags1,
                                       "wl1");  // 1:20
    processor->OnLogEvent(event.get());
    uint64_t dumpTimeNs = bucketStartTimeNs + 90 * NS_PER_SEC;  // 1:30
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_TRUE(reports.reports(0).metrics(0).has_duration_metrics());

    StatsLogReport::DurationMetricDataWrapper metricData;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).duration_metrics(), &metricData);
    ASSERT_EQ(metricData.data_size(), 1);
    DurationMetricData data = metricData.data(0);
    ASSERT_EQ(data.bucket_info_size(), 1);

    DurationBucketInfo bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.start_bucket_elapsed_nanos(), updateTimeNs);
    EXPECT_EQ(bucketInfo.end_bucket_elapsed_nanos(), dumpTimeNs);
    EXPECT_EQ(bucketInfo.duration_nanos(), 20 * NS_PER_SEC);
}

TEST(ConfigUpdateE2eTest, TestNewDurationExistingWhatSlicedCondition) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToBackgroundAtomMatcher();
    *config.add_atom_matcher() = CreateMoveToForegroundAtomMatcher();

    Predicate holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    // The predicate is dimensioning by first attribution node by uid.
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *config.add_predicate() = holdingWakelockPredicate;

    Predicate isInBackgroundPredicate = CreateIsInBackgroundPredicate();
    *isInBackgroundPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateDimensions(util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1 /*uid*/});
    *config.add_predicate() = isInBackgroundPredicate;

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    uint64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(FIVE_MINUTES) * 1000000LL;
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    int app1Uid = 123, app2Uid = 456;
    vector<int> attributionUids1 = {app1Uid};
    vector<string> attributionTags1 = {"App1"};
    vector<int> attributionUids2 = {app2Uid};
    vector<string> attributionTags2 = {"App2"};
    unique_ptr<LogEvent> event = CreateAcquireWakelockEvent(bucketStartTimeNs + 10 * NS_PER_SEC,
                                                            attributionUids1, attributionTags1,
                                                            "wl1");  // 0:10
    processor->OnLogEvent(event.get());
    event = CreateMoveToBackgroundEvent(bucketStartTimeNs + 22 * NS_PER_SEC, app1Uid);  // 0:22
    processor->OnLogEvent(event.get());
    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 35 * NS_PER_SEC, attributionUids2,
                                       attributionTags2,
                                       "wl1");  // 0:35
    processor->OnLogEvent(event.get());

    // Add metric.
    DurationMetric* durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->set_condition(isInBackgroundPredicate.id());
    durationMetric->set_aggregation_type(DurationMetric::SUM);
    // The metric is dimensioning by first attribution node and only by uid.
    *durationMetric->mutable_dimensions_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    durationMetric->set_bucket(FIVE_MINUTES);
    // Links between wakelock state atom and condition of app is in background.
    auto links = durationMetric->add_links();
    links->set_condition(isInBackgroundPredicate.id());
    *links->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *links->mutable_fields_in_condition() =
            CreateDimensions(util::ACTIVITY_FOREGROUND_STATE_CHANGED, {1 /*uid*/});

    uint64_t updateTimeNs = bucketStartTimeNs + 60 * NS_PER_SEC;  // 1:00
    processor->OnConfigUpdated(updateTimeNs, key, config, /*modular*/ true);

    event = CreateMoveToBackgroundEvent(bucketStartTimeNs + 73 * NS_PER_SEC, app2Uid);  // 1:13
    processor->OnLogEvent(event.get());
    event = CreateReleaseWakelockEvent(bucketStartTimeNs + 84 * NS_PER_SEC, attributionUids1,
                                       attributionTags1, "wl1");  // 1:24
    processor->OnLogEvent(event.get());

    uint64_t dumpTimeNs = bucketStartTimeNs + 90 * NS_PER_SEC;  //  1:30
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_TRUE(reports.reports(0).metrics(0).has_duration_metrics());

    StatsLogReport::DurationMetricDataWrapper metricData;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).duration_metrics(), &metricData);
    ASSERT_EQ(metricData.data_size(), 2);

    DurationMetricData data = metricData.data(0);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app1Uid);
    ASSERT_EQ(data.bucket_info_size(), 1);
    DurationBucketInfo bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 24 * NS_PER_SEC);

    data = metricData.data(1);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app2Uid);
    ASSERT_EQ(data.bucket_info_size(), 1);
    bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 17 * NS_PER_SEC);
}

TEST(ConfigUpdateE2eTest, TestNewDurationExistingWhatSlicedState) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");
    *config.add_atom_matcher() = CreateAcquireWakelockAtomMatcher();
    *config.add_atom_matcher() = CreateReleaseWakelockAtomMatcher();

    Predicate holdingWakelockPredicate = CreateHoldingWakelockPredicate();
    // The predicate is dimensioning by first attribution node by uid.
    *holdingWakelockPredicate.mutable_simple_predicate()->mutable_dimensions() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *config.add_predicate() = holdingWakelockPredicate;

    auto uidProcessState = CreateUidProcessState();
    *config.add_state() = uidProcessState;

    // Count metric. We don't care about this one. Only use it so the StateTracker gets persisted.
    CountMetric* countMetric = config.add_count_metric();
    countMetric->set_id(StringToId("Tmp"));
    countMetric->set_what(config.atom_matcher(0).id());
    countMetric->add_slice_by_state(uidProcessState.id());
    // The metric is dimensioning by first attribution node and only by uid.
    *countMetric->mutable_dimensions_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    countMetric->set_bucket(FIVE_MINUTES);
    auto stateLink = countMetric->add_state_link();
    stateLink->set_state_atom_id(util::UID_PROCESS_STATE_CHANGED);
    *stateLink->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *stateLink->mutable_fields_in_state() =
            CreateDimensions(util::UID_PROCESS_STATE_CHANGED, {1 /*uid*/});
    config.add_no_report_metric(countMetric->id());

    ConfigKey key(123, 987);
    uint64_t bucketStartTimeNs = 10000000000;  // 0:10
    uint64_t bucketSizeNs = TimeUnitToBucketSizeInMillis(FIVE_MINUTES) * 1000000LL;
    sp<StatsLogProcessor> processor =
            CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, key);

    int app1Uid = 123, app2Uid = 456;
    vector<int> attributionUids1 = {app1Uid};
    vector<string> attributionTags1 = {"App1"};
    vector<int> attributionUids2 = {app2Uid};
    vector<string> attributionTags2 = {"App2"};
    unique_ptr<LogEvent> event = CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 10 * NS_PER_SEC, app1Uid,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);  // 0:10
    processor->OnLogEvent(event.get());
    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 22 * NS_PER_SEC, attributionUids1,
                                       attributionTags1,
                                       "wl1");  // 0:22
    processor->OnLogEvent(event.get());
    event = CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 30 * NS_PER_SEC, app2Uid,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);  // 0:30
    processor->OnLogEvent(event.get());

    // Add metric.
    DurationMetric* durationMetric = config.add_duration_metric();
    durationMetric->set_id(StringToId("WakelockDuration"));
    durationMetric->set_what(holdingWakelockPredicate.id());
    durationMetric->add_slice_by_state(uidProcessState.id());
    durationMetric->set_aggregation_type(DurationMetric::SUM);
    // The metric is dimensioning by first attribution node and only by uid.
    *durationMetric->mutable_dimensions_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    durationMetric->set_bucket(FIVE_MINUTES);
    // Links between wakelock state atom and condition of app is in background.
    stateLink = durationMetric->add_state_link();
    stateLink->set_state_atom_id(util::UID_PROCESS_STATE_CHANGED);
    *stateLink->mutable_fields_in_what() =
            CreateAttributionUidDimensions(util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    *stateLink->mutable_fields_in_state() =
            CreateDimensions(util::UID_PROCESS_STATE_CHANGED, {1 /*uid*/});

    uint64_t updateTimeNs = bucketStartTimeNs + 60 * NS_PER_SEC;  // 1:00
    processor->OnConfigUpdated(updateTimeNs, key, config, /*modular*/ true);

    event = CreateAcquireWakelockEvent(bucketStartTimeNs + 72 * NS_PER_SEC, attributionUids2,
                                       attributionTags2,
                                       "wl1");  // 1:13
    processor->OnLogEvent(event.get());
    event = CreateUidProcessStateChangedEvent(
            bucketStartTimeNs + 75 * NS_PER_SEC, app1Uid,
            android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND);  // 1:15
    processor->OnLogEvent(event.get());
    event = CreateReleaseWakelockEvent(bucketStartTimeNs + 84 * NS_PER_SEC, attributionUids1,
                                       attributionTags1, "wl1");  // 1:24
    processor->OnLogEvent(event.get());

    uint64_t dumpTimeNs = bucketStartTimeNs + 90 * NS_PER_SEC;  //  1:30
    ConfigMetricsReportList reports;
    vector<uint8_t> buffer;
    processor->onDumpReport(key, dumpTimeNs, true, true, ADB_DUMP, FAST, &buffer);
    EXPECT_TRUE(reports.ParseFromArray(&buffer[0], buffer.size()));
    backfillDimensionPath(&reports);
    backfillStringInReport(&reports);
    backfillStartEndTimestamp(&reports);
    ASSERT_EQ(reports.reports_size(), 1);
    ASSERT_EQ(reports.reports(0).metrics_size(), 1);
    EXPECT_TRUE(reports.reports(0).metrics(0).has_duration_metrics());

    StatsLogReport::DurationMetricDataWrapper metricData;
    sortMetricDataByDimensionsValue(reports.reports(0).metrics(0).duration_metrics(), &metricData);
    ASSERT_EQ(metricData.data_size(), 3);

    DurationMetricData data = metricData.data(0);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app1Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    DurationBucketInfo bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 15 * NS_PER_SEC);

    data = metricData.data(1);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app1Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_BACKGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 9 * NS_PER_SEC);

    data = metricData.data(2);
    ValidateAttributionUidDimension(data.dimensions_in_what(), util::WAKELOCK_STATE_CHANGED,
                                    app2Uid);
    ValidateStateValue(data.slice_by_state(), util::UID_PROCESS_STATE_CHANGED,
                       android::app::ProcessStateEnum::PROCESS_STATE_IMPORTANT_FOREGROUND);
    ASSERT_EQ(data.bucket_info_size(), 1);
    bucketInfo = data.bucket_info(0);
    EXPECT_EQ(bucketInfo.duration_nanos(), 18 * NS_PER_SEC);
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
