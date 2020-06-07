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
#include "anomaly/AnomalyTracker.h"
#include "condition/ConditionTimer.h"
#include "condition/ConditionTracker.h"
#include "external/PullDataReceiver.h"
#include "external/StatsPullerManager.h"
#include "matchers/EventMatcherWizard.h"
#include "stats_log_util.h"
#include "MetricProducer.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

namespace android {
namespace os {
namespace statsd {

struct ValueBucket {
    int64_t mBucketStartNs;
    int64_t mBucketEndNs;
    std::vector<int> valueIndex;
    std::vector<Value> values;
    // If the metric has no condition, then this field is just wasted.
    // When we tune statsd memory usage in the future, this is a candidate to optimize.
    int64_t mConditionTrueNs;
};


// Aggregates values within buckets.
//
// There are different events that might complete a bucket
// - a condition change
// - an app upgrade
// - an alarm set to the end of the bucket
class ValueMetricProducer : public virtual MetricProducer, public virtual PullDataReceiver {
public:
    ValueMetricProducer(
            const ConfigKey& key, const ValueMetric& valueMetric, const int conditionIndex,
            const vector<ConditionState>& initialConditionCache,
            const sp<ConditionWizard>& conditionWizard, const int whatMatcherIndex,
            const sp<EventMatcherWizard>& matcherWizard, const int pullTagId,
            const int64_t timeBaseNs, const int64_t startTimeNs,
            const sp<StatsPullerManager>& pullerManager,
            const std::unordered_map<int, std::shared_ptr<Activation>>& eventActivationMap = {},
            const std::unordered_map<int, std::vector<std::shared_ptr<Activation>>>&
                    eventDeactivationMap = {},
            const vector<int>& slicedStateAtoms = {},
            const unordered_map<int, unordered_map<int, int64_t>>& stateGroupMap = {});

    virtual ~ValueMetricProducer();

    // Process data pulled on bucket boundary.
    void onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& data,
                      bool pullSuccess, int64_t originalPullTimeNs) override;

    // ValueMetric needs special logic if it's a pulled atom.
    void notifyAppUpgrade(const int64_t& eventTimeNs) override {
        std::lock_guard<std::mutex> lock(mMutex);
        if (!mSplitBucketForAppUpgrade) {
            return;
        }
        if (mIsPulled && mCondition == ConditionState::kTrue) {
            pullAndMatchEventsLocked(eventTimeNs);
        }
        flushCurrentBucketLocked(eventTimeNs, eventTimeNs);
    };

    // ValueMetric needs special logic if it's a pulled atom.
    void onStatsdInitCompleted(const int64_t& eventTimeNs) override {
        std::lock_guard<std::mutex> lock(mMutex);
        if (mIsPulled && mCondition == ConditionState::kTrue) {
            pullAndMatchEventsLocked(eventTimeNs);
        }
        flushCurrentBucketLocked(eventTimeNs, eventTimeNs);
    };

    void onStateChanged(int64_t eventTimeNs, int32_t atomId, const HashableDimensionKey& primaryKey,
                        const FieldValue& oldState, const FieldValue& newState) override;

protected:
    void onMatchedLogEventInternalLocked(
            const size_t matcherIndex, const MetricDimensionKey& eventKey,
            const ConditionKey& conditionKey, bool condition, const LogEvent& event,
            const std::map<int, HashableDimensionKey>& statePrimaryKeys) override;

private:
    void onDumpReportLocked(const int64_t dumpTimeNs,
                            const bool include_current_partial_bucket,
                            const bool erase_data,
                            const DumpLatency dumpLatency,
                            std::set<string> *str_set,
                            android::util::ProtoOutputStream* protoOutput) override;
    void clearPastBucketsLocked(const int64_t dumpTimeNs) override;

    // Internal interface to handle active state change.
    void onActiveStateChangedLocked(const int64_t& eventTimeNs) override;

    // Internal interface to handle condition change.
    void onConditionChangedLocked(const bool conditionMet, const int64_t eventTime) override;

    // Internal interface to handle sliced condition change.
    void onSlicedConditionMayChangeLocked(bool overallCondition, const int64_t eventTime) override;

    // Internal function to calculate the current used bytes.
    size_t byteSizeLocked() const override;

    void dumpStatesLocked(FILE* out, bool verbose) const override;

    // For pulled metrics, this method should only be called if a pull has be done. Else we will
    // not have complete data for the bucket.
    void flushIfNeededLocked(const int64_t& eventTime) override;

    // For pulled metrics, this method should only be called if a pulled have be done. Else we will
    // not have complete data for the bucket.
    void flushCurrentBucketLocked(const int64_t& eventTimeNs,
                                  const int64_t& nextBucketStartTimeNs) override;

    void prepareFirstBucketLocked() override;

    void dropDataLocked(const int64_t dropTimeNs) override;

    // Calculate previous bucket end time based on current time.
    int64_t calcPreviousBucketEndTime(const int64_t currentTimeNs);

    // Calculate how many buckets are present between the current bucket and eventTimeNs.
    int64_t calcBucketsForwardCount(const int64_t& eventTimeNs) const;

    // Mark the data as invalid.
    void invalidateCurrentBucket(const int64_t dropTimeNs, const BucketDropReason reason);

    void invalidateCurrentBucketWithoutResetBase(const int64_t dropTimeNs,
                                                 const BucketDropReason reason);

    // Skips the current bucket without notifying StatsdStats of the skipped bucket.
    // This should only be called from #flushCurrentBucketLocked. Otherwise, a future event that
    // causes the bucket to be invalidated will not notify StatsdStats.
    void skipCurrentBucket(const int64_t dropTimeNs, const BucketDropReason reason);

    const int mWhatMatcherIndex;

    sp<EventMatcherWizard> mEventMatcherWizard;

    sp<StatsPullerManager> mPullerManager;

    // Value fields for matching.
    std::vector<Matcher> mFieldMatchers;

    // Value fields for matching.
    std::set<HashableDimensionKey> mMatchedMetricDimensionKeys;

    // Holds the atom id, primary key pair from a state change.
    pair<int32_t, HashableDimensionKey> mStateChangePrimaryKey;

    // tagId for pulled data. -1 if this is not pulled
    const int mPullTagId;

    // if this is pulled metric
    const bool mIsPulled;

    // internal state of an ongoing aggregation bucket.
    typedef struct {
        // Index in multi value aggregation.
        int valueIndex;
        // Current value, depending on the aggregation type.
        Value value;
        // Number of samples collected.
        int sampleSize;
        // If this dimension has any non-tainted value. If not, don't report the
        // dimension.
        bool hasValue = false;
        // Whether new data is seen in the bucket.
        bool seenNewData = false;
    } Interval;

    typedef struct {
        // Holds current base value of the dimension. Take diff and update if necessary.
        Value base;
        // Whether there is a base to diff to.
        bool hasBase;
        // Last seen state value(s).
        HashableDimensionKey currentState;
        // Whether this dimensions in what key has a current state key.
        bool hasCurrentState;
    } BaseInfo;

    std::unordered_map<MetricDimensionKey, std::vector<Interval>> mCurrentSlicedBucket;

    std::unordered_map<HashableDimensionKey, std::vector<BaseInfo>> mCurrentBaseInfo;

    std::unordered_map<MetricDimensionKey, int64_t> mCurrentFullBucket;

    // Save the past buckets and we can clear when the StatsLogReport is dumped.
    std::unordered_map<MetricDimensionKey, std::vector<ValueBucket>> mPastBuckets;

    const int64_t mMinBucketSizeNs;

    // Util function to check whether the specified dimension hits the guardrail.
    bool hitGuardRailLocked(const MetricDimensionKey& newKey);

    bool hasReachedGuardRailLimit() const;

    bool hitFullBucketGuardRailLocked(const MetricDimensionKey& newKey);

    void pullAndMatchEventsLocked(const int64_t timestampNs);

    void accumulateEvents(const std::vector<std::shared_ptr<LogEvent>>& allData,
                          int64_t originalPullTimeNs, int64_t eventElapsedTimeNs);

    ValueBucket buildPartialBucket(int64_t bucketEndTime,
                                   const std::vector<Interval>& intervals);

    void initCurrentSlicedBucket(int64_t nextBucketStartTimeNs);

    void appendToFullBucket(const bool isFullBucketReached);

    // Reset diff base and mHasGlobalBase
    void resetBase();

    static const size_t kBucketSize = sizeof(ValueBucket{});

    const size_t mDimensionSoftLimit;

    const size_t mDimensionHardLimit;

    const bool mUseAbsoluteValueOnReset;

    const ValueMetric::AggregationType mAggregationType;

    const bool mUseDiff;

    const ValueMetric::ValueDirection mValueDirection;

    const bool mSkipZeroDiffOutput;

    // If true, use a zero value as base to compute the diff.
    // This is used for new keys which are present in the new data but was not
    // present in the base data.
    // The default base will only be used if we have a global base.
    const bool mUseZeroDefaultBase;

    // For pulled metrics, this is always set to true whenever a pull succeeds.
    // It is set to false when a pull fails, or upon condition change to false.
    // This is used to decide if we have the right base data to compute the
    // diff against.
    bool mHasGlobalBase;

    // This is to track whether or not the bucket is skipped for any of the reasons listed in
    // BucketDropReason, many of which make the bucket potentially invalid.
    bool mCurrentBucketIsSkipped;

    const int64_t mMaxPullDelayNs;

    const bool mSplitBucketForAppUpgrade;

    ConditionTimer mConditionTimer;

    FRIEND_TEST(ValueMetricProducerTest, TestAnomalyDetection);
    FRIEND_TEST(ValueMetricProducerTest, TestBaseSetOnConditionChange);
    FRIEND_TEST(ValueMetricProducerTest, TestBucketBoundariesOnConditionChange);
    FRIEND_TEST(ValueMetricProducerTest, TestBucketBoundaryNoCondition);
    FRIEND_TEST(ValueMetricProducerTest, TestBucketBoundaryWithCondition);
    FRIEND_TEST(ValueMetricProducerTest, TestBucketBoundaryWithCondition2);
    FRIEND_TEST(ValueMetricProducerTest, TestBucketInvalidIfGlobalBaseIsNotSet);
    FRIEND_TEST(ValueMetricProducerTest, TestCalcPreviousBucketEndTime);
    FRIEND_TEST(ValueMetricProducerTest, TestDataIsNotUpdatedWhenNoConditionChanged);
    FRIEND_TEST(ValueMetricProducerTest, TestEmptyDataResetsBase_onBucketBoundary);
    FRIEND_TEST(ValueMetricProducerTest, TestEmptyDataResetsBase_onConditionChanged);
    FRIEND_TEST(ValueMetricProducerTest, TestEmptyDataResetsBase_onDataPulled);
    FRIEND_TEST(ValueMetricProducerTest, TestEventsWithNonSlicedCondition);
    FRIEND_TEST(ValueMetricProducerTest, TestFirstBucket);
    FRIEND_TEST(ValueMetricProducerTest, TestLateOnDataPulledWithDiff);
    FRIEND_TEST(ValueMetricProducerTest, TestLateOnDataPulledWithoutDiff);
    FRIEND_TEST(ValueMetricProducerTest, TestPartialResetOnBucketBoundaries);
    FRIEND_TEST(ValueMetricProducerTest, TestPulledData_noDiff_bucketBoundaryFalse);
    FRIEND_TEST(ValueMetricProducerTest, TestPulledData_noDiff_bucketBoundaryTrue);
    FRIEND_TEST(ValueMetricProducerTest, TestPulledData_noDiff_withFailure);
    FRIEND_TEST(ValueMetricProducerTest, TestPulledData_noDiff_withMultipleConditionChanges);
    FRIEND_TEST(ValueMetricProducerTest, TestPulledData_noDiff_withoutCondition);
    FRIEND_TEST(ValueMetricProducerTest, TestPulledEventsNoCondition);
    FRIEND_TEST(ValueMetricProducerTest, TestPulledEventsTakeAbsoluteValueOnReset);
    FRIEND_TEST(ValueMetricProducerTest, TestPulledEventsTakeZeroOnReset);
    FRIEND_TEST(ValueMetricProducerTest, TestPulledEventsWithFiltering);
    FRIEND_TEST(ValueMetricProducerTest, TestPulledWithAppUpgradeDisabled);
    FRIEND_TEST(ValueMetricProducerTest, TestPushedAggregateAvg);
    FRIEND_TEST(ValueMetricProducerTest, TestPushedAggregateMax);
    FRIEND_TEST(ValueMetricProducerTest, TestPushedAggregateMin);
    FRIEND_TEST(ValueMetricProducerTest, TestPushedAggregateSum);
    FRIEND_TEST(ValueMetricProducerTest, TestPushedEventsWithCondition);
    FRIEND_TEST(ValueMetricProducerTest, TestPushedEventsWithoutCondition);
    FRIEND_TEST(ValueMetricProducerTest, TestResetBaseOnPullDelayExceeded);
    FRIEND_TEST(ValueMetricProducerTest, TestResetBaseOnPullFailAfterConditionChange);
    FRIEND_TEST(ValueMetricProducerTest, TestResetBaseOnPullFailAfterConditionChange_EndOfBucket);
    FRIEND_TEST(ValueMetricProducerTest, TestResetBaseOnPullFailBeforeConditionChange);
    FRIEND_TEST(ValueMetricProducerTest, TestResetBaseOnPullTooLate);
    FRIEND_TEST(ValueMetricProducerTest, TestSkipZeroDiffOutput);
    FRIEND_TEST(ValueMetricProducerTest, TestSkipZeroDiffOutputMultiValue);
    FRIEND_TEST(ValueMetricProducerTest, TestSlicedState);
    FRIEND_TEST(ValueMetricProducerTest, TestSlicedStateWithMap);
    FRIEND_TEST(ValueMetricProducerTest, TestSlicedStateWithPrimaryField_WithDimensions);
    FRIEND_TEST(ValueMetricProducerTest, TestSlicedStateWithCondition);
    FRIEND_TEST(ValueMetricProducerTest, TestTrimUnusedDimensionKey);
    FRIEND_TEST(ValueMetricProducerTest, TestUseZeroDefaultBase);
    FRIEND_TEST(ValueMetricProducerTest, TestUseZeroDefaultBaseWithPullFailures);

    FRIEND_TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenOneConditionFailed);
    FRIEND_TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenInitialPullFailed);
    FRIEND_TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenLastPullFailed);
    FRIEND_TEST(ValueMetricProducerTest_BucketDrop, TestInvalidBucketWhenGuardRailHit);
    FRIEND_TEST(ValueMetricProducerTest_BucketDrop,
                TestInvalidBucketWhenAccumulateEventWrongBucket);

    FRIEND_TEST(ValueMetricProducerTest_PartialBucket, TestBucketBoundariesOnPartialBucket);
    FRIEND_TEST(ValueMetricProducerTest_PartialBucket, TestFullBucketResetWhenLastBucketInvalid);
    FRIEND_TEST(ValueMetricProducerTest_PartialBucket, TestPartialBucketCreated);
    FRIEND_TEST(ValueMetricProducerTest_PartialBucket, TestPushedEvents);
    FRIEND_TEST(ValueMetricProducerTest_PartialBucket, TestPulledValue);
    FRIEND_TEST(ValueMetricProducerTest_PartialBucket, TestPulledValueWhileConditionFalse);

    friend class ValueMetricProducerTestHelper;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
