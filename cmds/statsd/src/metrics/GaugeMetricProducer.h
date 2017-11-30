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

#include <unordered_map>

#include <android/util/ProtoOutputStream.h>
#include <gtest/gtest_prod.h>
#include "../condition/ConditionTracker.h"
#include "../external/PullDataReceiver.h"
#include "../external/StatsPullerManager.h"
#include "../matchers/matcher_util.h"
#include "MetricProducer.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "stats_util.h"

namespace android {
namespace os {
namespace statsd {

struct GaugeBucket {
    int64_t mBucketStartNs;
    int64_t mBucketEndNs;
    int64_t mGauge;
    uint64_t mBucketNum;
};

// This gauge metric producer first register the puller to automatically pull the gauge at the
// beginning of each bucket. If the condition is met, insert it to the bucket info. Otherwise
// proactively pull the gauge when the condition is changed to be true. Therefore, the gauge metric
// producer always reports the guage at the earliest time of the bucket when the condition is met.
class GaugeMetricProducer : public virtual MetricProducer, public virtual PullDataReceiver {
public:
    // TODO: Pass in the start time from MetricsManager, it should be consistent
    // for all metrics.
    GaugeMetricProducer(const ConfigKey& key, const GaugeMetric& countMetric,
                        const int conditionIndex, const sp<ConditionWizard>& wizard,
                        const int pullTagId, const int64_t startTimeNs);

    virtual ~GaugeMetricProducer();

    // Handles when the pulled data arrives.
    void onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& data) override;

    void onConditionChanged(const bool conditionMet, const uint64_t eventTime) override;
    void onSlicedConditionMayChange(const uint64_t eventTime) override;

    void finish() override;
    void flushIfNeeded(const uint64_t newEventTime) override;

    // TODO: Pass a timestamp as a parameter in onDumpReport.
    std::unique_ptr<std::vector<uint8_t>> onDumpReport() override;

    size_t byteSize() const override;

    // TODO: Implement this later.
    virtual void notifyAppUpgrade(const string& apk, const int uid, const int version) override{};
    // TODO: Implement this later.
    virtual void notifyAppRemoved(const string& apk, const int uid) override{};

protected:
    void onMatchedLogEventInternal(const size_t matcherIndex, const HashableDimensionKey& eventKey,
                                   const std::map<std::string, HashableDimensionKey>& conditionKey,
                                   bool condition, const LogEvent& event,
                                   bool scheduledPull) override;

    void startNewProtoOutputStream(long long timestamp) override;

private:
    // The default bucket size for gauge metric is 1 second.
    static const uint64_t kDefaultGaugemBucketSizeNs = 1000 * 1000 * 1000;
    const GaugeMetric mMetric;

    StatsPullerManager mStatsPullerManager;
    // tagId for pulled data. -1 if this is not pulled
    const int mPullTagId;

    Mutex mLock;

    // Save the past buckets and we can clear when the StatsLogReport is dumped.
    // TODO: Add a lock to mPastBuckets.
    std::unordered_map<HashableDimensionKey, std::vector<GaugeBucket>> mPastBuckets;

    // The current bucket.
    std::shared_ptr<DimToValMap> mCurrentSlicedBucket = std::make_shared<DimToValMap>();

    int64_t getGauge(const LogEvent& event);

    bool hitGuardRail(const HashableDimensionKey& newKey);

    static const size_t kBucketSize = sizeof(GaugeBucket{});

    FRIEND_TEST(GaugeMetricProducerTest, TestWithCondition);
    FRIEND_TEST(GaugeMetricProducerTest, TestNoCondition);
    FRIEND_TEST(GaugeMetricProducerTest, TestAnomalyDetection);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
