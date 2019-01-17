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
#include "../matchers/EventMatcherWizard.h"
#include "MetricProducer.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "../stats_util.h"

namespace android {
namespace os {
namespace statsd {

struct GaugeAtom {
    GaugeAtom(std::shared_ptr<vector<FieldValue>> fields, int64_t elapsedTimeNs)
        : mFields(fields), mElapsedTimestamps(elapsedTimeNs) {
    }
    std::shared_ptr<vector<FieldValue>> mFields;
    int64_t mElapsedTimestamps;
};

struct GaugeBucket {
    int64_t mBucketStartNs;
    int64_t mBucketEndNs;
    std::vector<GaugeAtom> mGaugeAtoms;
};

typedef std::unordered_map<MetricDimensionKey, std::vector<GaugeAtom>>
    DimToGaugeAtomsMap;

// This gauge metric producer first register the puller to automatically pull the gauge at the
// beginning of each bucket. If the condition is met, insert it to the bucket info. Otherwise
// proactively pull the gauge when the condition is changed to be true. Therefore, the gauge metric
// producer always reports the guage at the earliest time of the bucket when the condition is met.
class GaugeMetricProducer : public virtual MetricProducer, public virtual PullDataReceiver {
public:
    GaugeMetricProducer(const ConfigKey& key, const GaugeMetric& gaugeMetric,
                        const int conditionIndex, const sp<ConditionWizard>& conditionWizard,
                        const int whatMatcherIndex,
                        const sp<EventMatcherWizard>& matcherWizard,
                        const int pullTagId, const int triggerAtomId, const int atomId,
                        const int64_t timeBaseNs, const int64_t startTimeNs,
                        const sp<StatsPullerManager>& pullerManager);

    virtual ~GaugeMetricProducer();

    // Handles when the pulled data arrives.
    void onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& data) override;

    // GaugeMetric needs to immediately trigger another pull when we create the partial bucket.
    void notifyAppUpgrade(const int64_t& eventTimeNs, const string& apk, const int uid,
                          const int64_t version) override {
        std::lock_guard<std::mutex> lock(mMutex);

        if (!mSplitBucketForAppUpgrade) {
            return;
        }
        if (eventTimeNs > getCurrentBucketEndTimeNs()) {
            // Flush full buckets on the normal path up to the latest bucket boundary.
            flushIfNeededLocked(eventTimeNs);
        }
        flushCurrentBucketLocked(eventTimeNs);
        mCurrentBucketStartTimeNs = eventTimeNs;
        if (mIsPulled && mSamplingType == GaugeMetric::RANDOM_ONE_SAMPLE) {
            pullAndMatchEventsLocked(eventTimeNs);
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
                            const bool erase_data,
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
    void flushIfNeededLocked(const int64_t& eventTime) override;

    void flushCurrentBucketLocked(const int64_t& eventTimeNs) override;

    void pullAndMatchEventsLocked(const int64_t timestampNs);

    const int mWhatMatcherIndex;

    sp<EventMatcherWizard> mEventMatcherWizard;

    sp<StatsPullerManager> mPullerManager;
    // tagId for pulled data. -1 if this is not pulled
    const int mPullTagId;

    // tagId for atoms that trigger the pulling, if any
    const int mTriggerAtomId;

    // tagId for output atom
    const int mAtomId;

    // if this is pulled metric
    const bool mIsPulled;

    // Save the past buckets and we can clear when the StatsLogReport is dumped.
    std::unordered_map<MetricDimensionKey, std::vector<GaugeBucket>> mPastBuckets;

    // The current partial bucket.
    std::shared_ptr<DimToGaugeAtomsMap> mCurrentSlicedBucket;

    // The current full bucket for anomaly detection. This is updated to the latest value seen for
    // this slice (ie, for partial buckets, we use the last partial bucket in this full bucket).
    std::shared_ptr<DimToValMap> mCurrentSlicedBucketForAnomaly;

    // Pairs of (elapsed start, elapsed end) denoting buckets that were skipped.
    std::list<std::pair<int64_t, int64_t>> mSkippedBuckets;

    const int64_t mMinBucketSizeNs;

    // Translate Atom based bucket to single numeric value bucket for anomaly and updates the map
    // for each slice with the latest value.
    void updateCurrentSlicedBucketForAnomaly();

    // Whitelist of fields to report. Empty means all are reported.
    std::vector<Matcher> mFieldMatchers;

    GaugeMetric::SamplingType mSamplingType;

    const int64_t mMaxPullDelayNs;

    // apply a whitelist on the original input
    std::shared_ptr<vector<FieldValue>> getGaugeFields(const LogEvent& event);

    // Util function to check whether the specified dimension hits the guardrail.
    bool hitGuardRailLocked(const MetricDimensionKey& newKey);

    static const size_t kBucketSize = sizeof(GaugeBucket{});

    const size_t mDimensionSoftLimit;

    const size_t mDimensionHardLimit;

    const size_t mGaugeAtomsPerDimensionLimit;

    const bool mSplitBucketForAppUpgrade;

    FRIEND_TEST(GaugeMetricProducerTest, TestPulledEventsWithCondition);
    FRIEND_TEST(GaugeMetricProducerTest, TestPulledEventsWithSlicedCondition);
    FRIEND_TEST(GaugeMetricProducerTest, TestPulledEventsNoCondition);
    FRIEND_TEST(GaugeMetricProducerTest, TestPushedEventsWithUpgrade);
    FRIEND_TEST(GaugeMetricProducerTest, TestPulledWithUpgrade);
    FRIEND_TEST(GaugeMetricProducerTest, TestPulledWithAppUpgradeDisabled);
    FRIEND_TEST(GaugeMetricProducerTest, TestPulledEventsAnomalyDetection);
    FRIEND_TEST(GaugeMetricProducerTest, TestFirstBucket);
    FRIEND_TEST(GaugeMetricProducerTest, TestPullOnTrigger);
    FRIEND_TEST(GaugeMetricProducerTest, TestRemoveDimensionInOutput);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
