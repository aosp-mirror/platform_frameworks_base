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

#include <gtest/gtest.h>

#include <binder/IPCThreadState.h>
#include "src/StatsLogProcessor.h"
#include "src/StatsService.h"
#include "src/stats_log_util.h"
#include "tests/statsd_test_util.h"

#include <vector>

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

const string kAndroid = "android";
const string kApp1 = "app1.sharing.1";
const int kConfigKey = 789130123;  // Randomly chosen to avoid collisions with existing configs.

void SendConfig(StatsService& service, const StatsdConfig& config) {
    string str;
    config.SerializeToString(&str);
    std::vector<uint8_t> configAsVec(str.begin(), str.end());
    bool success;
    service.addConfiguration(kConfigKey, configAsVec, String16(kAndroid.c_str()));
}

ConfigMetricsReport GetReports(sp<StatsLogProcessor> processor, int64_t timestamp,
                               bool include_current = false) {
    vector<uint8_t> output;
    IPCThreadState* ipc = IPCThreadState::self();
    ConfigKey configKey(ipc->getCallingUid(), kConfigKey);
    processor->onDumpReport(configKey, timestamp, include_current /* include_current_bucket*/,
                            true/* include strings*/, ADB_DUMP, &output);
    ConfigMetricsReportList reports;
    reports.ParseFromArray(output.data(), output.size());
    EXPECT_EQ(1, reports.reports_size());
    return reports.reports(0);
}

StatsdConfig MakeConfig() {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto appCrashMatcher = CreateProcessCrashAtomMatcher();
    *config.add_atom_matcher() = appCrashMatcher;
    auto countMetric = config.add_count_metric();
    countMetric->set_id(StringToId("AppCrashes"));
    countMetric->set_what(appCrashMatcher.id());
    countMetric->set_bucket(FIVE_MINUTES);
    return config;
}

StatsdConfig MakeValueMetricConfig(int64_t minTime) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto temperatureAtomMatcher = CreateTemperatureAtomMatcher();
    *config.add_atom_matcher() = temperatureAtomMatcher;
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();

    auto valueMetric = config.add_value_metric();
    valueMetric->set_id(123456);
    valueMetric->set_what(temperatureAtomMatcher.id());
    *valueMetric->mutable_value_field() =
            CreateDimensions(android::util::TEMPERATURE, {3 /* temperature degree field */});
    *valueMetric->mutable_dimensions_in_what() =
            CreateDimensions(android::util::TEMPERATURE, {2 /* sensor name field */});
    valueMetric->set_bucket(FIVE_MINUTES);
    valueMetric->set_min_bucket_size_nanos(minTime);
    return config;
}

StatsdConfig MakeGaugeMetricConfig(int64_t minTime) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT");  // LogEvent defaults to UID of root.

    auto temperatureAtomMatcher = CreateTemperatureAtomMatcher();
    *config.add_atom_matcher() = temperatureAtomMatcher;
    *config.add_atom_matcher() = CreateScreenTurnedOnAtomMatcher();
    *config.add_atom_matcher() = CreateScreenTurnedOffAtomMatcher();

    auto gaugeMetric = config.add_gauge_metric();
    gaugeMetric->set_id(123456);
    gaugeMetric->set_what(temperatureAtomMatcher.id());
    gaugeMetric->mutable_gauge_fields_filter()->set_include_all(true);
    *gaugeMetric->mutable_dimensions_in_what() =
            CreateDimensions(android::util::TEMPERATURE, {2 /* sensor name field */});
    gaugeMetric->set_bucket(FIVE_MINUTES);
    gaugeMetric->set_min_bucket_size_nanos(minTime);
    return config;
}

TEST(PartialBucketE2eTest, TestCountMetricWithoutSplit) {
    StatsService service(nullptr);
    SendConfig(service, MakeConfig());
    int64_t start = getElapsedRealtimeNs();  // This is the start-time the metrics producers are
                                             // initialized with.

    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 1).get());
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 2).get());

    ConfigMetricsReport report = GetReports(service.mProcessor, start + 3);
    // Expect no metrics since the bucket has not finished yet.
    EXPECT_EQ(0, report.metrics_size());
}

TEST(PartialBucketE2eTest, TestCountMetricNoSplitOnNewApp) {
    StatsService service(nullptr);
    SendConfig(service, MakeConfig());
    int64_t start = getElapsedRealtimeNs();  // This is the start-time the metrics producers are
                                             // initialized with.

    // Force the uidmap to update at timestamp 2.
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 1).get());
    // This is a new installation, so there shouldn't be a split (should be same as the without
    // split case).
    service.mUidMap->updateApp(start + 2, String16(kApp1.c_str()), 1, 2);
    // Goes into the second bucket.
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 3).get());

    ConfigMetricsReport report = GetReports(service.mProcessor, start + 4);
    EXPECT_EQ(0, report.metrics_size());
}

TEST(PartialBucketE2eTest, TestCountMetricSplitOnUpgrade) {
    StatsService service(nullptr);
    SendConfig(service, MakeConfig());
    int64_t start = getElapsedRealtimeNs();  // This is the start-time the metrics producers are
                                             // initialized with.
    service.mUidMap->updateMap(start, {1}, {1}, {String16(kApp1.c_str())});

    // Force the uidmap to update at timestamp 2.
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 1).get());
    service.mUidMap->updateApp(start + 2, String16(kApp1.c_str()), 1, 2);
    // Goes into the second bucket.
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 3).get());

    ConfigMetricsReport report = GetReports(service.mProcessor, start + 4);
    backfillStartEndTimestamp(&report);
    EXPECT_EQ(1, report.metrics_size());
    EXPECT_TRUE(report.metrics(0).count_metrics().data(0).bucket_info(0).
                    has_start_bucket_elapsed_nanos());
    EXPECT_TRUE(report.metrics(0).count_metrics().data(0).bucket_info(0).
                    has_end_bucket_elapsed_nanos());
    EXPECT_EQ(1, report.metrics(0).count_metrics().data(0).bucket_info(0).count());
}

TEST(PartialBucketE2eTest, TestCountMetricSplitOnRemoval) {
    StatsService service(nullptr);
    SendConfig(service, MakeConfig());
    int64_t start = getElapsedRealtimeNs();  // This is the start-time the metrics producers are
                                             // initialized with.
    service.mUidMap->updateMap(start, {1}, {1}, {String16(kApp1.c_str())});

    // Force the uidmap to update at timestamp 2.
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 1).get());
    service.mUidMap->removeApp(start + 2, String16(kApp1.c_str()), 1);
    // Goes into the second bucket.
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 3).get());

    ConfigMetricsReport report = GetReports(service.mProcessor, start + 4);
    backfillStartEndTimestamp(&report);
    EXPECT_EQ(1, report.metrics_size());
    EXPECT_TRUE(report.metrics(0).count_metrics().data(0).bucket_info(0).
                    has_start_bucket_elapsed_nanos());
    EXPECT_TRUE(report.metrics(0).count_metrics().data(0).bucket_info(0).
                    has_end_bucket_elapsed_nanos());
    EXPECT_EQ(1, report.metrics(0).count_metrics().data(0).bucket_info(0).count());
}

TEST(PartialBucketE2eTest, TestValueMetricWithoutMinPartialBucket) {
    StatsService service(nullptr);
    // Partial buckets don't occur when app is first installed.
    service.mUidMap->updateApp(1, String16(kApp1.c_str()), 1, 1);
    SendConfig(service, MakeValueMetricConfig(0));
    int64_t start = getElapsedRealtimeNs();  // This is the start-time the metrics producers are
                                             // initialized with.

    service.mProcessor->informPullAlarmFired(5 * 60 * NS_PER_SEC + start);
    service.mUidMap->updateApp(5 * 60 * NS_PER_SEC + start + 2, String16(kApp1.c_str()), 1, 2);

    ConfigMetricsReport report =
            GetReports(service.mProcessor, 5 * 60 * NS_PER_SEC + start + 100, true);
    EXPECT_EQ(1, report.metrics_size());
    EXPECT_EQ(0, report.metrics(0).value_metrics().skipped_size());
}

TEST(PartialBucketE2eTest, TestValueMetricWithMinPartialBucket) {
    StatsService service(nullptr);
    // Partial buckets don't occur when app is first installed.
    service.mUidMap->updateApp(1, String16(kApp1.c_str()), 1, 1);
    SendConfig(service, MakeValueMetricConfig(60 * NS_PER_SEC /* One minute */));
    int64_t start = getElapsedRealtimeNs();  // This is the start-time the metrics producers are
                                             // initialized with.

    const int64_t endSkipped = 5 * 60 * NS_PER_SEC + start + 2;
    service.mProcessor->informPullAlarmFired(5 * 60 * NS_PER_SEC + start);
    service.mUidMap->updateApp(endSkipped, String16(kApp1.c_str()), 1, 2);

    ConfigMetricsReport report =
            GetReports(service.mProcessor, 5 * 60 * NS_PER_SEC + start + 100 * NS_PER_SEC, true);
    backfillStartEndTimestamp(&report);
    EXPECT_EQ(1, report.metrics_size());
    EXPECT_EQ(1, report.metrics(0).value_metrics().skipped_size());
    EXPECT_TRUE(report.metrics(0).value_metrics().skipped(0).has_start_bucket_elapsed_nanos());
    // Can't test the start time since it will be based on the actual time when the pulling occurs.
    EXPECT_EQ(MillisToNano(NanoToMillis(endSkipped)),
              report.metrics(0).value_metrics().skipped(0).end_bucket_elapsed_nanos());
}

TEST(PartialBucketE2eTest, TestGaugeMetricWithoutMinPartialBucket) {
    StatsService service(nullptr);
    // Partial buckets don't occur when app is first installed.
    service.mUidMap->updateApp(1, String16(kApp1.c_str()), 1, 1);
    SendConfig(service, MakeGaugeMetricConfig(0));
    int64_t start = getElapsedRealtimeNs();  // This is the start-time the metrics producers are
                                             // initialized with.

    service.mProcessor->informPullAlarmFired(5 * 60 * NS_PER_SEC + start);
    service.mUidMap->updateApp(5 * 60 * NS_PER_SEC + start + 2, String16(kApp1.c_str()), 1, 2);

    ConfigMetricsReport report =
            GetReports(service.mProcessor, 5 * 60 * NS_PER_SEC + start + 100, true);
    EXPECT_EQ(1, report.metrics_size());
    EXPECT_EQ(0, report.metrics(0).gauge_metrics().skipped_size());
}

TEST(PartialBucketE2eTest, TestGaugeMetricWithMinPartialBucket) {
    StatsService service(nullptr);
    // Partial buckets don't occur when app is first installed.
    service.mUidMap->updateApp(1, String16(kApp1.c_str()), 1, 1);
    SendConfig(service, MakeGaugeMetricConfig(60 * NS_PER_SEC /* One minute */));
    int64_t start = getElapsedRealtimeNs();  // This is the start-time the metrics producers are
                                             // initialized with.

    const int64_t endSkipped = 5 * 60 * NS_PER_SEC + start + 2;
    service.mProcessor->informPullAlarmFired(5 * 60 * NS_PER_SEC + start);
    service.mUidMap->updateApp(endSkipped, String16(kApp1.c_str()), 1, 2);

    ConfigMetricsReport report =
            GetReports(service.mProcessor, 5 * 60 * NS_PER_SEC + start + 100 * NS_PER_SEC, true);
    backfillStartEndTimestamp(&report);
    EXPECT_EQ(1, report.metrics_size());
    EXPECT_EQ(1, report.metrics(0).gauge_metrics().skipped_size());
    // Can't test the start time since it will be based on the actual time when the pulling occurs.
    EXPECT_TRUE(report.metrics(0).gauge_metrics().skipped(0).has_start_bucket_elapsed_nanos());
    EXPECT_EQ(MillisToNano(NanoToMillis(endSkipped)),
              report.metrics(0).gauge_metrics().skipped(0).end_bucket_elapsed_nanos());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
