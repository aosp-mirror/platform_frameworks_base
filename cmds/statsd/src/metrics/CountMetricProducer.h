/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef COUNT_METRIC_PRODUCER_H
#define COUNT_METRIC_PRODUCER_H

#include <unordered_map>

#include <android/util/ProtoOutputStream.h>
#include <gtest/gtest_prod.h>
#include "../anomaly/AnomalyTracker.h"
#include "../condition/ConditionTracker.h"
#include "../matchers/matcher_util.h"
#include "MetricProducer.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "stats_util.h"

namespace android {
namespace os {
namespace statsd {

struct CountBucket {
    int64_t mBucketStartNs;
    int64_t mBucketEndNs;
    int64_t mCount;
};

class CountMetricProducer : public MetricProducer {
public:
    // TODO: Pass in the start time from MetricsManager, it should be consistent for all metrics.
    CountMetricProducer(const ConfigKey& key, const CountMetric& countMetric,
                        const int conditionIndex, const sp<ConditionWizard>& wizard,
                        const int64_t startTimeNs);

    virtual ~CountMetricProducer();

protected:
    void onMatchedLogEventInternalLocked(
            const size_t matcherIndex, const MetricDimensionKey& eventKey,
            const ConditionKey& conditionKey, bool condition,
            const LogEvent& event) override;

private:

    void onDumpReportLocked(const int64_t dumpTimeNs,
                            const bool include_current_partial_bucket,
                            std::set<string> *str_set,
                            android::util::ProtoOutputStream* protoOutput) override;

    void clearPastBucketsLocked(const int64_t dumpTimeNs) override;

    // Internal interface to handle condition change.
    void onConditionChangedLocked(const bool conditionMet, const int64_t eventTime) override;

    // Internal interface to handle sliced condition change.
    void onSlicedConditionMayChangeLocked(bool overallCondition, const int64_t eventTime) override;

    // Internal function to calculate the current used bytes.
    size_t byteSizeLocked() const override;

    void dumpStatesLocked(FILE* out, bool verbose) const override;

    void dropDataLocked(const int64_t dropTimeNs) override;

    // Util function to flush the old packet.
    void flushIfNeededLocked(const int64_t& newEventTime) override;

    void flushCurrentBucketLocked(const int64_t& eventTimeNs) override;

    // TODO: Add a lock to mPastBuckets.
    std::unordered_map<MetricDimensionKey, std::vector<CountBucket>> mPastBuckets;

    // The current bucket (may be a partial bucket).
    std::shared_ptr<DimToValMap> mCurrentSlicedCounter = std::make_shared<DimToValMap>();

    // The sum of previous partial buckets in the current full bucket (excluding the current
    // partial bucket). This is only updated while flushing the current bucket.
    std::shared_ptr<DimToValMap> mCurrentFullCounters = std::make_shared<DimToValMap>();

    static const size_t kBucketSize = sizeof(CountBucket{});

    bool hitGuardRailLocked(const MetricDimensionKey& newKey);

    FRIEND_TEST(CountMetricProducerTest, TestNonDimensionalEvents);
    FRIEND_TEST(CountMetricProducerTest, TestEventsWithNonSlicedCondition);
    FRIEND_TEST(CountMetricProducerTest, TestEventsWithSlicedCondition);
    FRIEND_TEST(CountMetricProducerTest, TestAnomalyDetectionUnSliced);
    FRIEND_TEST(CountMetricProducerTest, TestEventWithAppUpgrade);
    FRIEND_TEST(CountMetricProducerTest, TestEventWithAppUpgradeInNextBucket);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // COUNT_METRIC_PRODUCER_H
