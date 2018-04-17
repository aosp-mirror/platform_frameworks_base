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

#pragma once

#include <gtest/gtest_prod.h>
#include <utils/threads.h>
#include <list>
#include "../anomaly/AnomalyTracker.h"
#include "../condition/ConditionTracker.h"
#include "../external/PullDataReceiver.h"
#include "../external/StatsPullerManager.h"
#include "MetricProducer.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

namespace android {
namespace os {
namespace statsd {

struct ValueBucket {
    int64_t mBucketStartNs;
    int64_t mBucketEndNs;
    int64_t mValue;
};

class ValueMetricProducer : public virtual MetricProducer, public virtual PullDataReceiver {
public:
    ValueMetricProducer(const ConfigKey& key, const ValueMetric& valueMetric,
                        const int conditionIndex, const sp<ConditionWizard>& wizard,
                        const int pullTagId, const int64_t timeBaseNs, const int64_t startTimeNs);

    virtual ~ValueMetricProducer();

    void onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& data) override;

    // ValueMetric needs special logic if it's a pulled atom.
    void notifyAppUpgrade(const int64_t& eventTimeNs, const string& apk, const int uid,
                          const int64_t version) override {
        std::lock_guard<std::mutex> lock(mMutex);

        if (mPullTagId != -1) {
            vector<shared_ptr<LogEvent>> allData;
            mStatsPullerManager->Pull(mPullTagId, eventTimeNs, &allData);
            if (allData.size() == 0) {
                // This shouldn't happen since this valuemetric is not useful now.
            }

            // Pretend the pulled data occurs right before the app upgrade event.
            mCondition = false;
            for (const auto& data : allData) {
                data->setElapsedTimestampNs(eventTimeNs - 1);
                onMatchedLogEventLocked(0, *data);
            }

            flushCurrentBucketLocked(eventTimeNs);
            mCurrentBucketStartTimeNs = eventTimeNs;

            mCondition = true;
            for (const auto& data : allData) {
                data->setElapsedTimestampNs(eventTimeNs);
                onMatchedLogEventLocked(0, *data);
            }
        } else {  // For pushed value metric, we simply flush and reset the current bucket start.
            flushCurrentBucketLocked(eventTimeNs);
            mCurrentBucketStartTimeNs = eventTimeNs;
        }
    };

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

    // Internal interface to handle condition change.
    void onConditionChangedLocked(const bool conditionMet, const int64_t eventTime) override;

    // Internal interface to handle sliced condition change.
    void onSlicedConditionMayChangeLocked(bool overallCondition, const int64_t eventTime) override;

    // Internal function to calculate the current used bytes.
    size_t byteSizeLocked() const override;

    void dumpStatesLocked(FILE* out, bool verbose) const override;

    // Util function to flush the old packet.
    void flushIfNeededLocked(const int64_t& eventTime) override;

    void flushCurrentBucketLocked(const int64_t& eventTimeNs) override;

    void dropDataLocked(const int64_t dropTimeNs) override;

    const FieldMatcher mValueField;

    std::shared_ptr<StatsPullerManager> mStatsPullerManager;

    // for testing
    ValueMetricProducer(const ConfigKey& key, const ValueMetric& valueMetric,
                        const int conditionIndex, const sp<ConditionWizard>& wizard,
                        const int pullTagId, const int64_t timeBaseNs, const int64_t startTimeNs,
                        std::shared_ptr<StatsPullerManager> statsPullerManager);

    // tagId for pulled data. -1 if this is not pulled
    const int mPullTagId;

    int mField;

    // internal state of a bucket.
    typedef struct {
        // Pulled data always come in pair of <start, end>. This holds the value
        // for start. The diff (end - start) is added to sum.
        int64_t start;
        // Whether the start data point is updated
        bool startUpdated;
        // If end data point comes before the start, record this pair as tainted
        // and the value is not added to the running sum.
        int tainted;
        // Running sum of known pairs in this bucket
        int64_t sum;
        // If this dimension has any non-tainted value. If not, don't report the
        // dimension.
        bool hasValue;
    } Interval;

    std::unordered_map<MetricDimensionKey, Interval> mCurrentSlicedBucket;

    std::unordered_map<MetricDimensionKey, int64_t> mCurrentFullBucket;

    // Save the past buckets and we can clear when the StatsLogReport is dumped.
    // TODO: Add a lock to mPastBuckets.
    std::unordered_map<MetricDimensionKey, std::vector<ValueBucket>> mPastBuckets;

    // Pairs of (elapsed start, elapsed end) denoting buckets that were skipped.
    std::list<std::pair<int64_t, int64_t>> mSkippedBuckets;

    const int64_t mMinBucketSizeNs;

    // Util function to check whether the specified dimension hits the guardrail.
    bool hitGuardRailLocked(const MetricDimensionKey& newKey);

    static const size_t kBucketSize = sizeof(ValueBucket{});

    const size_t mDimensionSoftLimit;

    const size_t mDimensionHardLimit;

    FRIEND_TEST(ValueMetricProducerTest, TestNonDimensionalEvents);
    FRIEND_TEST(ValueMetricProducerTest, TestEventsWithNonSlicedCondition);
    FRIEND_TEST(ValueMetricProducerTest, TestPushedEventsWithUpgrade);
    FRIEND_TEST(ValueMetricProducerTest, TestPulledValueWithUpgrade);
    FRIEND_TEST(ValueMetricProducerTest, TestPushedEventsWithoutCondition);
    FRIEND_TEST(ValueMetricProducerTest, TestAnomalyDetection);
    FRIEND_TEST(ValueMetricProducerTest, TestBucketBoundaryNoCondition);
    FRIEND_TEST(ValueMetricProducerTest, TestBucketBoundaryWithCondition);
    FRIEND_TEST(ValueMetricProducerTest, TestBucketBoundaryWithCondition2);
    FRIEND_TEST(ValueMetricProducerTest, TestBucketBoundaryWithCondition3);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
