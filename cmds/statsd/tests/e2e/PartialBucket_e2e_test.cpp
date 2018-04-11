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

#include "src/StatsLogProcessor.h"
#include "src/StatsService.h"
#include "src/stats_log_util.h"
#include "tests/statsd_test_util.h"

#include <vector>

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

const string kApp1 = "app1.sharing.1";
const int kConfigKey = 789130123;  // Randomly chosen to avoid collisions with existing configs.

void SendConfig(StatsService& service, const StatsdConfig& config) {
    string str;
    config.SerializeToString(&str);
    std::vector<uint8_t> configAsVec(str.begin(), str.end());
    bool success;
    service.addConfiguration(kConfigKey, configAsVec);
}

ConfigMetricsReport GetReports(StatsService& service) {
    vector<uint8_t> output;
    service.getData(kConfigKey, &output);
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

TEST(PartialBucketE2eTest, TestCountMetricWithoutSplit) {
    StatsService service(nullptr);
    SendConfig(service, MakeConfig());
    const long start = getElapsedRealtimeNs();  // This is the start-time the metrics producers are
                                                // initialized with.

    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 1).get());
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 2).get());

    ConfigMetricsReport report = GetReports(service);
    // Expect no metrics since the bucket has not finished yet.
    EXPECT_EQ(0, report.metrics_size());
}

TEST(PartialBucketE2eTest, TestCountMetricNoSplitOnNewApp) {
    StatsService service(nullptr);
    SendConfig(service, MakeConfig());
    const long start = getElapsedRealtimeNs();  // This is the start-time the metrics producers are
                                                // initialized with.

    // Force the uidmap to update at timestamp 2.
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 1).get());
    // This is a new installation, so there shouldn't be a split (should be same as the without
    // split case).
    service.mUidMap->updateApp(start + 2, String16(kApp1.c_str()), 1, 2);
    // Goes into the second bucket.
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 3).get());

    ConfigMetricsReport report = GetReports(service);
    EXPECT_EQ(0, report.metrics_size());
}

TEST(PartialBucketE2eTest, TestCountMetricSplitOnUpgrade) {
    StatsService service(nullptr);
    SendConfig(service, MakeConfig());
    const long start = getElapsedRealtimeNs();  // This is the start-time the metrics producers are
                                                // initialized with.
    service.mUidMap->updateMap(start, {1}, {1}, {String16(kApp1.c_str())});

    // Force the uidmap to update at timestamp 2.
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 1).get());
    service.mUidMap->updateApp(start + 2, String16(kApp1.c_str()), 1, 2);
    // Goes into the second bucket.
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 3).get());

    ConfigMetricsReport report = GetReports(service);
    EXPECT_EQ(1, report.metrics_size());
    EXPECT_EQ(1, report.metrics(0).count_metrics().data(0).bucket_info(0).count());
}

TEST(PartialBucketE2eTest, TestCountMetricSplitOnRemoval) {
    StatsService service(nullptr);
    SendConfig(service, MakeConfig());
    const long start = getElapsedRealtimeNs();  // This is the start-time the metrics producers are
                                                // initialized with.
    service.mUidMap->updateMap(start, {1}, {1}, {String16(kApp1.c_str())});

    // Force the uidmap to update at timestamp 2.
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 1).get());
    service.mUidMap->removeApp(start + 2, String16(kApp1.c_str()), 1);
    // Goes into the second bucket.
    service.mProcessor->OnLogEvent(CreateAppCrashEvent(100, start + 3).get());

    ConfigMetricsReport report = GetReports(service);
    EXPECT_EQ(1, report.metrics_size());
    EXPECT_EQ(1, report.metrics(0).count_metrics().data(0).bucket_info(0).count());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android