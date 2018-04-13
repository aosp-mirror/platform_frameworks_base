// Copyright (C) 2018 The Android Open Source Project
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
#include "src/stats_log_util.h"
#include "tests/statsd_test_util.h"

#include <vector>

namespace android {
namespace os {
namespace statsd {

#ifdef __ANDROID__

namespace {

StatsdConfig CreateStatsdConfig(int num_buckets, int threshold) {
    StatsdConfig config;
    config.add_allowed_log_source("AID_ROOT"); // LogEvent defaults to UID of root.
    auto wakelockAcquireMatcher = CreateAcquireWakelockAtomMatcher();

    *config.add_atom_matcher() = wakelockAcquireMatcher;

    auto countMetric = config.add_count_metric();
    countMetric->set_id(123456);
    countMetric->set_what(wakelockAcquireMatcher.id());
    *countMetric->mutable_dimensions_in_what() = CreateAttributionUidDimensions(
            android::util::WAKELOCK_STATE_CHANGED, {Position::FIRST});
    countMetric->set_bucket(FIVE_MINUTES);

    auto alert = config.add_alert();
    alert->set_id(StringToId("alert"));
    alert->set_metric_id(123456);
    alert->set_num_buckets(num_buckets);
    alert->set_refractory_period_secs(10);
    alert->set_trigger_if_sum_gt(threshold);

    // Two hours
    config.set_ttl_in_seconds(2 * 3600);
    return config;
}

}  // namespace

TEST(ConfigTtlE2eTest, TestCountMetric) {
    const int num_buckets = 1;
    const int threshold = 3;
    auto config = CreateStatsdConfig(num_buckets, threshold);
    const uint64_t alert_id = config.alert(0).id();
    const uint32_t refractory_period_sec = config.alert(0).refractory_period_secs();

    int64_t bucketStartTimeNs = 10000000000;
    int64_t bucketSizeNs =
        TimeUnitToBucketSizeInMillis(config.count_metric(0).bucket()) * 1000000;

    ConfigKey cfgKey;
    auto processor = CreateStatsLogProcessor(bucketStartTimeNs, bucketStartTimeNs, config, cfgKey);
    EXPECT_EQ(processor->mMetricsManagers.size(), 1u);
    EXPECT_TRUE(processor->mMetricsManagers.begin()->second->isConfigValid());

    std::vector<AttributionNodeInternal> attributions1 = {CreateAttribution(111, "App1")};

    FieldValue fieldValue1(Field(android::util::WAKELOCK_STATE_CHANGED, (int32_t)0x02010101),
                           Value((int32_t)111));
    HashableDimensionKey whatKey1({fieldValue1});
    MetricDimensionKey dimensionKey1(whatKey1, DEFAULT_DIMENSION_KEY);

    FieldValue fieldValue2(Field(android::util::WAKELOCK_STATE_CHANGED, (int32_t)0x02010101),
                           Value((int32_t)222));
    HashableDimensionKey whatKey2({fieldValue2});
    MetricDimensionKey dimensionKey2(whatKey2, DEFAULT_DIMENSION_KEY);

    auto event = CreateAcquireWakelockEvent(attributions1, "wl1", bucketStartTimeNs + 2);
    processor->OnLogEvent(event.get());

    event = CreateAcquireWakelockEvent(attributions1, "wl2", bucketStartTimeNs + bucketSizeNs + 2);
    processor->OnLogEvent(event.get());

    event = CreateAcquireWakelockEvent(
        attributions1, "wl1", bucketStartTimeNs + 25 * bucketSizeNs + 2);
    processor->OnLogEvent(event.get());

    EXPECT_EQ((int64_t)(bucketStartTimeNs + 25 * bucketSizeNs + 2 + 2 * 3600 * NS_PER_SEC),
              processor->mMetricsManagers.begin()->second->getTtlEndNs());
}


#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
