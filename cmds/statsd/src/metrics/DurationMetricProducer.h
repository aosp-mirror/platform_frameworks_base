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
#include "../anomaly/DurationAnomalyTracker.h"
#include "../condition/ConditionTracker.h"
#include "../matchers/matcher_util.h"
#include "MetricProducer.h"
#include "duration_helper/DurationTracker.h"
#include "duration_helper/MaxDurationTracker.h"
#include "duration_helper/OringDurationTracker.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "stats_util.h"

using namespace std;

namespace android {
namespace os {
namespace statsd {

class DurationMetricProducer : public MetricProducer {
public:
    DurationMetricProducer(
            const ConfigKey& key, const DurationMetric& durationMetric, const int conditionIndex,
            const vector<ConditionState>& initialConditionCache, const int whatIndex,
            const int startIndex, const int stopIndex, const int stopAllIndex, const bool nesting,
            const sp<ConditionWizard>& wizard, const uint64_t protoHash,
            const FieldMatcher& internalDimensions, const int64_t timeBaseNs,
            const int64_t startTimeNs,
            const unordered_map<int, shared_ptr<Activation>>& eventActivationMap = {},
            const unordered_map<int, vector<shared_ptr<Activation>>>& eventDeactivationMap = {},
            const vector<int>& slicedStateAtoms = {},
            const unordered_map<int, unordered_map<int, int64_t>>& stateGroupMap = {});

    virtual ~DurationMetricProducer();

    sp<AnomalyTracker> addAnomalyTracker(const Alert &alert,
                                         const sp<AlarmMonitor>& anomalyAlarmMonitor) override;

    void addAnomalyTracker(sp<AnomalyTracker>& anomalyTracker) override;

    void onStateChanged(const int64_t eventTimeNs, const int32_t atomId,
                        const HashableDimensionKey& primaryKey, const FieldValue& oldState,
                        const FieldValue& newState) override;

    MetricType getMetricType() const override {
        return METRIC_TYPE_DURATION;
    }

protected:
    void onMatchedLogEventLocked(const size_t matcherIndex, const LogEvent& event) override;

    void onMatchedLogEventInternalLocked(
            const size_t matcherIndex, const MetricDimensionKey& eventKey,
            const ConditionKey& conditionKeys, bool condition, const LogEvent& event,
            const std::map<int, HashableDimensionKey>& statePrimaryKeys) override;

private:
    // Initializes true dimensions of the 'what' predicate. Only to be called during initialization.
    void initTrueDimensions(const int whatIndex, const int64_t startTimeNs);

    void handleMatchedLogEventValuesLocked(const size_t matcherIndex,
                                           const std::vector<FieldValue>& values,
                                           const int64_t eventTimeNs);
    void handleStartEvent(const MetricDimensionKey& eventKey, const ConditionKey& conditionKeys,
                          bool condition, const int64_t eventTimeNs,
                          const vector<FieldValue>& eventValues);

    void onDumpReportLocked(const int64_t dumpTimeNs,
                            const bool include_current_partial_bucket,
                            const bool erase_data,
                            const DumpLatency dumpLatency,
                            std::set<string> *str_set,
                            android::util::ProtoOutputStream* protoOutput) override;

    void clearPastBucketsLocked(const int64_t dumpTimeNs) override;

    // Internal interface to handle condition change.
    void onConditionChangedLocked(const bool conditionMet, const int64_t eventTime) override;

    // Internal interface to handle active state change.
    void onActiveStateChangedLocked(const int64_t& eventTimeNs) override;

    // Internal interface to handle sliced condition change.
    void onSlicedConditionMayChangeLocked(bool overallCondition, const int64_t eventTime) override;

    void onSlicedConditionMayChangeInternalLocked(bool overallCondition,
                                                  const int64_t eventTimeNs);

    void onSlicedConditionMayChangeLocked_opt1(bool overallCondition, const int64_t eventTime);
    void onSlicedConditionMayChangeLocked_opt2(bool overallCondition, const int64_t eventTime);

    // Internal function to calculate the current used bytes.
    size_t byteSizeLocked() const override;

    void dumpStatesLocked(FILE* out, bool verbose) const override;

    void dropDataLocked(const int64_t dropTimeNs) override;

    // Util function to flush the old packet.
    void flushIfNeededLocked(const int64_t& eventTime);

    void flushCurrentBucketLocked(const int64_t& eventTimeNs,
                                  const int64_t& nextBucketStartTimeNs) override;

    bool onConfigUpdatedLocked(
            const StatsdConfig& config, const int configIndex, const int metricIndex,
            const std::vector<sp<AtomMatchingTracker>>& allAtomMatchingTrackers,
            const std::unordered_map<int64_t, int>& oldAtomMatchingTrackerMap,
            const std::unordered_map<int64_t, int>& newAtomMatchingTrackerMap,
            const sp<EventMatcherWizard>& matcherWizard,
            const std::vector<sp<ConditionTracker>>& allConditionTrackers,
            const std::unordered_map<int64_t, int>& conditionTrackerMap,
            const sp<ConditionWizard>& wizard,
            const std::unordered_map<int64_t, int>& metricToActivationMap,
            std::unordered_map<int, std::vector<int>>& trackerToMetricMap,
            std::unordered_map<int, std::vector<int>>& conditionToMetricMap,
            std::unordered_map<int, std::vector<int>>& activationAtomTrackerToMetricMap,
            std::unordered_map<int, std::vector<int>>& deactivationAtomTrackerToMetricMap,
            std::vector<int>& metricsWithActivation) override;

    void addAnomalyTrackerLocked(sp<AnomalyTracker>& anomalyTracker);

    const DurationMetric_AggregationType mAggregationType;

    // Index of the SimpleAtomMatcher which defines the start.
    int mStartIndex;

    // Index of the SimpleAtomMatcher which defines the stop.
    int mStopIndex;

    // Index of the SimpleAtomMatcher which defines the stop all for all dimensions.
    int mStopAllIndex;

    // nest counting -- for the same key, stops must match the number of starts to make real stop
    const bool mNested;

    // The dimension from the atom predicate. e.g., uid, wakelock name.
    vector<Matcher> mInternalDimensions;

    bool mContainANYPositionInInternalDimensions;

    // This boolean is true iff When mInternalDimensions == mDimensionsInWhat
    bool mUseWhatDimensionAsInternalDimension;

    // Caches the current unsliced part condition.
    ConditionState mUnSlicedPartCondition;

    // Save the past buckets and we can clear when the StatsLogReport is dumped.
    std::unordered_map<MetricDimensionKey, std::vector<DurationBucket>> mPastBuckets;

    // The duration trackers in the current bucket.
    std::unordered_map<HashableDimensionKey, std::unique_ptr<DurationTracker>>
            mCurrentSlicedDurationTrackerMap;

    // Helper function to create a duration tracker given the metric aggregation type.
    std::unique_ptr<DurationTracker> createDurationTracker(
            const MetricDimensionKey& eventKey) const;

    // Util function to check whether the specified dimension hits the guardrail.
    bool hitGuardRailLocked(const MetricDimensionKey& newKey);

    static const size_t kBucketSize = sizeof(DurationBucket{});

    FRIEND_TEST(DurationMetricTrackerTest, TestNoCondition);
    FRIEND_TEST(DurationMetricTrackerTest, TestNonSlicedCondition);
    FRIEND_TEST(DurationMetricTrackerTest, TestNonSlicedConditionUnknownState);
    FRIEND_TEST(WakelockDurationE2eTest, TestAggregatedPredicates);
    FRIEND_TEST(DurationMetricTrackerTest, TestFirstBucket);

    FRIEND_TEST(DurationMetricProducerTest_PartialBucket, TestSumDuration);
    FRIEND_TEST(DurationMetricProducerTest_PartialBucket,
                TestSumDurationWithSplitInFollowingBucket);
    FRIEND_TEST(DurationMetricProducerTest_PartialBucket, TestMaxDuration);
    FRIEND_TEST(DurationMetricProducerTest_PartialBucket, TestMaxDurationWithSplitInNextBucket);

    FRIEND_TEST(ConfigUpdateTest, TestUpdateDurationMetrics);
    FRIEND_TEST(ConfigUpdateTest, TestUpdateAlerts);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
