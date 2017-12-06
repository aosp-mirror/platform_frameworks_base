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
    uint64_t mBucketNum;
};

class ValueMetricProducer : public virtual MetricProducer, public virtual PullDataReceiver {
public:
    ValueMetricProducer(const ConfigKey& key, const ValueMetric& valueMetric,
                        const int conditionIndex, const sp<ConditionWizard>& wizard,
                        const int pullTagId, const uint64_t startTimeNs);

    virtual ~ValueMetricProducer();

    void finish() override;

    void onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& data) override;

    // TODO: Implement this later.
    virtual void notifyAppUpgrade(const string& apk, const int uid, const int64_t version)
            override{};
    // TODO: Implement this later.
    virtual void notifyAppRemoved(const string& apk, const int uid) override{};

protected:
    void onMatchedLogEventInternalLocked(
            const size_t matcherIndex, const HashableDimensionKey& eventKey,
            const std::map<std::string, HashableDimensionKey>& conditionKey, bool condition,
            const LogEvent& event, bool scheduledPull) override;

private:
    // TODO: Pass a timestamp as a parameter in onDumpReport.
    std::unique_ptr<std::vector<uint8_t>> onDumpReportLocked() override;

    // Internal interface to handle condition change.
    void onConditionChangedLocked(const bool conditionMet, const uint64_t eventTime) override;

    // Internal interface to handle sliced condition change.
    void onSlicedConditionMayChangeLocked(const uint64_t eventTime) override;

    // Internal function to calculate the current used bytes.
    size_t byteSizeLocked() const override;

    // Util function to flush the old packet.
    void flushIfNeededLocked(const uint64_t& eventTime);

    // Util function to init/reset the proto output stream.
    void startNewProtoOutputStreamLocked(long long timestamp);

    const ValueMetric mMetric;

    std::shared_ptr<StatsPullerManager> mStatsPullerManager;

    // for testing
    ValueMetricProducer(const ConfigKey& key, const ValueMetric& valueMetric,
                        const int conditionIndex, const sp<ConditionWizard>& wizard,
                        const int pullTagId, const uint64_t startTimeNs,
                        std::shared_ptr<StatsPullerManager> statsPullerManager);

    // tagId for pulled data. -1 if this is not pulled
    const int mPullTagId;

    // internal state of a bucket.
    typedef struct {
        std::vector<std::pair<long, long>> raw;
        bool tainted;
    } Interval;

    std::unordered_map<HashableDimensionKey, Interval> mCurrentSlicedBucket;
    // If condition is true and pulling on schedule, the previous bucket value needs to be carried
    // over to the next bucket.
    std::unordered_map<HashableDimensionKey, Interval> mNextSlicedBucket;

    // Save the past buckets and we can clear when the StatsLogReport is dumped.
    // TODO: Add a lock to mPastBuckets.
    std::unordered_map<HashableDimensionKey, std::vector<ValueBucket>> mPastBuckets;

    long get_value(const LogEvent& event);

    // Util function to check whether the specified dimension hits the guardrail.
    bool hitGuardRailLocked(const HashableDimensionKey& newKey);

    static const size_t kBucketSize = sizeof(ValueBucket{});

    FRIEND_TEST(ValueMetricProducerTest, TestNonDimensionalEvents);
    FRIEND_TEST(ValueMetricProducerTest, TestEventsWithNonSlicedCondition);
    FRIEND_TEST(ValueMetricProducerTest, TestPushedEventsWithoutCondition);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
