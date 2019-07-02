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

#ifndef METRIC_PRODUCER_H
#define METRIC_PRODUCER_H

#include <shared_mutex>

#include <frameworks/base/cmds/statsd/src/active_config_list.pb.h>
#include "HashableDimensionKey.h"
#include "anomaly/AnomalyTracker.h"
#include "condition/ConditionWizard.h"
#include "config/ConfigKey.h"
#include "matchers/matcher_util.h"
#include "packages/PackageInfoListener.h"

#include <log/logprint.h>
#include <utils/RefBase.h>
#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

// Keep this in sync with DumpReportReason enum in stats_log.proto
enum DumpReportReason {
    DEVICE_SHUTDOWN = 1,
    CONFIG_UPDATED = 2,
    CONFIG_REMOVED = 3,
    GET_DATA_CALLED = 4,
    ADB_DUMP = 5,
    CONFIG_RESET = 6,
    STATSCOMPANION_DIED = 7,
    TERMINATION_SIGNAL_RECEIVED = 8
};

// If the metric has no activation requirement, it will be active once the metric producer is
// created.
// If the metric needs to be activated by atoms, the metric producer will start
// with kNotActive state, turn to kActive or kActiveOnBoot when the activation event arrives, become
// kNotActive when it reaches the duration limit (timebomb). If the activation event arrives again
// before or after it expires, the event producer will be re-activated and ttl will be reset.
enum ActivationState {
    kNotActive = 0,
    kActive = 1,
    kActiveOnBoot = 2,
};

enum DumpLatency {
    // In some cases, we only have a short time range to do the dump, e.g. statsd is being killed.
    // We might be able to return all the data in this mode. For instance, pull metrics might need
    // to be pulled when the current bucket is requested.
    FAST = 1,
    // In other cases, it is fine for a dump to take more than a few milliseconds, e.g. config
    // updates.
    NO_TIME_CONSTRAINTS = 2
};

// A MetricProducer is responsible for compute one single metrics, creating stats log report, and
// writing the report to dropbox. MetricProducers should respond to package changes as required in
// PackageInfoListener, but if none of the metrics are slicing by package name, then the update can
// be a no-op.
class MetricProducer : public virtual PackageInfoListener {
public:
    MetricProducer(const int64_t& metricId, const ConfigKey& key, const int64_t timeBaseNs,
                   const int conditionIndex, const sp<ConditionWizard>& wizard)
        : mMetricId(metricId),
          mConfigKey(key),
          mTimeBaseNs(timeBaseNs),
          mCurrentBucketStartTimeNs(timeBaseNs),
          mCurrentBucketNum(0),
          mCondition(initialCondition(conditionIndex)),
          mConditionSliced(false),
          mWizard(wizard),
          mConditionTrackerIndex(conditionIndex),
          mContainANYPositionInDimensionsInWhat(false),
          mSliceByPositionALL(false),
          mSameConditionDimensionsInTracker(false),
          mHasLinksToAllConditionDimensionsInTracker(false),
          mIsActive(true) {
    }

    virtual ~MetricProducer(){};

    ConditionState initialCondition(const int conditionIndex) const {
        return conditionIndex >= 0 ? ConditionState::kUnknown : ConditionState::kTrue;
    }

    /**
     * Forces this metric to split into a partial bucket right now. If we're past a full bucket, we
     * first call the standard flushing code to flush up to the latest full bucket. Then we call
     * the flush again when the end timestamp is forced to be now, and then after flushing, update
     * the start timestamp to be now.
     */
    void notifyAppUpgrade(const int64_t& eventTimeNs, const string& apk, const int uid,
                          const int64_t version) override {
        std::lock_guard<std::mutex> lock(mMutex);

        if (eventTimeNs > getCurrentBucketEndTimeNs()) {
            // Flush full buckets on the normal path up to the latest bucket boundary.
            flushIfNeededLocked(eventTimeNs);
        }
        // Now flush a partial bucket.
        flushCurrentBucketLocked(eventTimeNs, eventTimeNs);
        // Don't update the current bucket number so that the anomaly tracker knows this bucket
        // is a partial bucket and can merge it with the previous bucket.
    };

    void notifyAppRemoved(const int64_t& eventTimeNs, const string& apk, const int uid) override{
        // Force buckets to split on removal also.
        notifyAppUpgrade(eventTimeNs, apk, uid, 0);
    };

    void onUidMapReceived(const int64_t& eventTimeNs) override{
            // Purposefully don't flush partial buckets on a new snapshot.
            // This occurs if a new user is added/removed or statsd crashes.
    };

    // Consume the parsed stats log entry that already matched the "what" of the metric.
    void onMatchedLogEvent(const size_t matcherIndex, const LogEvent& event) {
        std::lock_guard<std::mutex> lock(mMutex);
        onMatchedLogEventLocked(matcherIndex, event);
    }

    void onConditionChanged(const bool condition, const int64_t eventTime) {
        std::lock_guard<std::mutex> lock(mMutex);
        onConditionChangedLocked(condition, eventTime);
    }

    void onSlicedConditionMayChange(bool overallCondition, const int64_t eventTime) {
        std::lock_guard<std::mutex> lock(mMutex);
        onSlicedConditionMayChangeLocked(overallCondition, eventTime);
    }

    bool isConditionSliced() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mConditionSliced;
    };

    // Output the metrics data to [protoOutput]. All metrics reports end with the same timestamp.
    // This method clears all the past buckets.
    void onDumpReport(const int64_t dumpTimeNs,
                      const bool include_current_partial_bucket,
                      const bool erase_data,
                      const DumpLatency dumpLatency,
                      std::set<string> *str_set,
                      android::util::ProtoOutputStream* protoOutput) {
        std::lock_guard<std::mutex> lock(mMutex);
        return onDumpReportLocked(dumpTimeNs, include_current_partial_bucket, erase_data,
                dumpLatency, str_set, protoOutput);
    }

    void clearPastBuckets(const int64_t dumpTimeNs) {
        std::lock_guard<std::mutex> lock(mMutex);
        return clearPastBucketsLocked(dumpTimeNs);
    }

    void dumpStates(FILE* out, bool verbose) const {
        std::lock_guard<std::mutex> lock(mMutex);
        dumpStatesLocked(out, verbose);
    }

    // Returns the memory in bytes currently used to store this metric's data. Does not change
    // state.
    size_t byteSize() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return byteSizeLocked();
    }

    /* If alert is valid, adds an AnomalyTracker and returns it. If invalid, returns nullptr. */
    virtual sp<AnomalyTracker> addAnomalyTracker(const Alert &alert,
                                                 const sp<AlarmMonitor>& anomalyAlarmMonitor) {
        std::lock_guard<std::mutex> lock(mMutex);
        sp<AnomalyTracker> anomalyTracker = new AnomalyTracker(alert, mConfigKey);
        if (anomalyTracker != nullptr) {
            mAnomalyTrackers.push_back(anomalyTracker);
        }
        return anomalyTracker;
    }

    int64_t getBuckeSizeInNs() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mBucketSizeNs;
    }

    // Only needed for unit-testing to override guardrail.
    void setBucketSize(int64_t bucketSize) {
        mBucketSizeNs = bucketSize;
    }

    inline const int64_t& getMetricId() const {
        return mMetricId;
    }

    void loadActiveMetric(const ActiveMetric& activeMetric, int64_t currentTimeNs) {
        std::lock_guard<std::mutex> lock(mMutex);
        loadActiveMetricLocked(activeMetric, currentTimeNs);
    }

    // Let MetricProducer drop in-memory data to save memory.
    // We still need to keep future data valid and anomaly tracking work, which means we will
    // have to flush old data, informing anomaly trackers then safely drop old data.
    // We still keep current bucket data for future metrics' validity.
    void dropData(const int64_t dropTimeNs) {
        std::lock_guard<std::mutex> lock(mMutex);
        dropDataLocked(dropTimeNs);
    }

    // For test only.
    inline int64_t getCurrentBucketNum() const {
        return mCurrentBucketNum;
    }

    void activate(int activationTrackerIndex, int64_t elapsedTimestampNs) {
        std::lock_guard<std::mutex> lock(mMutex);
        activateLocked(activationTrackerIndex, elapsedTimestampNs);
    }

    void cancelEventActivation(int deactivationTrackerIndex) {
        std::lock_guard<std::mutex> lock(mMutex);
        cancelEventActivationLocked(deactivationTrackerIndex);
    }

    bool isActive() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return isActiveLocked();
    }

    void addActivation(int activationTrackerIndex, const ActivationType& activationType,
            int64_t ttl_seconds, int deactivationTrackerIndex = -1);

    void prepareFirstBucket() {
        std::lock_guard<std::mutex> lock(mMutex);
        prepareFirstBucketLocked();
    }

    void flushIfExpire(int64_t elapsedTimestampNs);

    void writeActiveMetricToProtoOutputStream(
            int64_t currentTimeNs, const DumpReportReason reason, ProtoOutputStream* proto);
protected:
    virtual void onConditionChangedLocked(const bool condition, const int64_t eventTime) = 0;
    virtual void onSlicedConditionMayChangeLocked(bool overallCondition,
                                                  const int64_t eventTime) = 0;
    virtual void onDumpReportLocked(const int64_t dumpTimeNs,
                                    const bool include_current_partial_bucket,
                                    const bool erase_data,
                                    const DumpLatency dumpLatency,
                                    std::set<string> *str_set,
                                    android::util::ProtoOutputStream* protoOutput) = 0;
    virtual void clearPastBucketsLocked(const int64_t dumpTimeNs) = 0;
    virtual size_t byteSizeLocked() const = 0;
    virtual void dumpStatesLocked(FILE* out, bool verbose) const = 0;

    bool evaluateActiveStateLocked(int64_t elapsedTimestampNs);

    void activateLocked(int activationTrackerIndex, int64_t elapsedTimestampNs);
    void cancelEventActivationLocked(int deactivationTrackerIndex);

    inline bool isActiveLocked() const {
        return mIsActive;
    }

    void loadActiveMetricLocked(const ActiveMetric& activeMetric, int64_t currentTimeNs);

    virtual void prepareFirstBucketLocked() {};
    /**
     * Flushes the current bucket if the eventTime is after the current bucket's end time. This will
       also flush the current partial bucket in memory.
     */
    virtual void flushIfNeededLocked(const int64_t& eventTime){};

    /**
     * Flushes all the data including the current partial bucket.
     */
    virtual void flushLocked(const int64_t& eventTimeNs) {
        flushIfNeededLocked(eventTimeNs);
        flushCurrentBucketLocked(eventTimeNs, eventTimeNs);
    };

    /**
     * For metrics that aggregate (ie, every metric producer except for EventMetricProducer),
     * we need to be able to flush the current buckets on demand (ie, end the current bucket and
     * start new bucket). If this function is called when eventTimeNs is greater than the current
     * bucket's end timestamp, than we flush up to the end of the latest full bucket; otherwise,
     * we assume that we want to flush a partial bucket. The bucket start timestamp and bucket
     * number are not changed by this function. This method should only be called by
     * flushIfNeededLocked or flushLocked or the app upgrade handler; the caller MUST update the
     * bucket timestamp and bucket number as needed.
     */
    virtual void flushCurrentBucketLocked(const int64_t& eventTimeNs,
                                          const int64_t& nextBucketStartTimeNs) {};

    virtual void onActiveStateChangedLocked(const int64_t& eventTimeNs) {
        if (!mIsActive) {
            flushLocked(eventTimeNs);
        }
    }

    // Convenience to compute the current bucket's end time, which is always aligned with the
    // start time of the metric.
    int64_t getCurrentBucketEndTimeNs() const {
        return mTimeBaseNs + (mCurrentBucketNum + 1) * mBucketSizeNs;
    }

    int64_t getBucketNumFromEndTimeNs(const int64_t endNs) {
        return (endNs - mTimeBaseNs) / mBucketSizeNs - 1;
    }

    virtual void dropDataLocked(const int64_t dropTimeNs) = 0;

    const int64_t mMetricId;

    const ConfigKey mConfigKey;

    // The time when this metric producer was first created. The end time for the current bucket
    // can be computed from this based on mCurrentBucketNum.
    int64_t mTimeBaseNs;

    // Start time may not be aligned with the start of statsd if there is an app upgrade in the
    // middle of a bucket.
    int64_t mCurrentBucketStartTimeNs;

    // Used by anomaly detector to track which bucket we are in. This is not sent with the produced
    // report.
    int64_t mCurrentBucketNum;

    int64_t mBucketSizeNs;

    ConditionState mCondition;

    bool mConditionSliced;

    sp<ConditionWizard> mWizard;

    int mConditionTrackerIndex;

    vector<Matcher> mDimensionsInWhat;       // The dimensions_in_what defined in statsd_config
    vector<Matcher> mDimensionsInCondition;  // The dimensions_in_condition defined in statsd_config

    bool mContainANYPositionInDimensionsInWhat;
    bool mSliceByPositionALL;

    // True iff the condition dimensions equal to the sliced dimensions in the simple condition
    // tracker. This field is always false for combinational condition trackers.
    bool mSameConditionDimensionsInTracker;

    // True iff the metric to condition links cover all dimension fields in the condition tracker.
    // This field is always false for combinational condition trackers.
    bool mHasLinksToAllConditionDimensionsInTracker;

    std::vector<Metric2Condition> mMetric2ConditionLinks;

    std::vector<sp<AnomalyTracker>> mAnomalyTrackers;

    /*
     * Individual metrics can implement their own business logic here. All pre-processing is done.
     *
     * [matcherIndex]: the index of the matcher which matched this event. This is interesting to
     *                 DurationMetric, because it has start/stop/stop_all 3 matchers.
     * [eventKey]: the extracted dimension key for the final output. if the metric doesn't have
     *             dimensions, it will be DEFAULT_DIMENSION_KEY
     * [conditionKey]: the keys of conditions which should be used to query the condition for this
     *                 target event (from MetricConditionLink). This is passed to individual metrics
     *                 because DurationMetric needs it to be cached.
     * [condition]: whether condition is met. If condition is sliced, this is the result coming from
     *              query with ConditionWizard; If condition is not sliced, this is the
     *              nonSlicedCondition.
     * [event]: the log event, just in case the metric needs its data, e.g., EventMetric.
     */
    virtual void onMatchedLogEventInternalLocked(
            const size_t matcherIndex, const MetricDimensionKey& eventKey,
            const ConditionKey& conditionKey, bool condition,
            const LogEvent& event) = 0;

    // Consume the parsed stats log entry that already matched the "what" of the metric.
    virtual void onMatchedLogEventLocked(const size_t matcherIndex, const LogEvent& event);

    mutable std::mutex mMutex;

    struct Activation {
        Activation(const ActivationType& activationType, const int64_t ttlNs)
            : ttl_ns(ttlNs),
              start_ns(0),
              state(ActivationState::kNotActive),
              activationType(activationType) {}

        const int64_t ttl_ns;
        int64_t start_ns;
        ActivationState state;
        const ActivationType activationType;
    };
    // When the metric producer has multiple activations, these activations are ORed to determine
    // whether the metric producer is ready to generate metrics.
    std::unordered_map<int, std::shared_ptr<Activation>> mEventActivationMap;

    // Maps index of atom matcher for deactivation to a list of Activation structs.
    std::unordered_map<int, std::vector<std::shared_ptr<Activation>>> mEventDeactivationMap;

    bool mIsActive;

    FRIEND_TEST(DurationMetricE2eTest, TestOneBucket);
    FRIEND_TEST(DurationMetricE2eTest, TestTwoBuckets);
    FRIEND_TEST(DurationMetricE2eTest, TestWithActivation);
    FRIEND_TEST(DurationMetricE2eTest, TestWithCondition);
    FRIEND_TEST(DurationMetricE2eTest, TestWithSlicedCondition);
    FRIEND_TEST(DurationMetricE2eTest, TestWithActivationAndSlicedCondition);

    FRIEND_TEST(MetricActivationE2eTest, TestCountMetric);
    FRIEND_TEST(MetricActivationE2eTest, TestCountMetricWithOneDeactivation);
    FRIEND_TEST(MetricActivationE2eTest, TestCountMetricWithTwoDeactivations);
    FRIEND_TEST(MetricActivationE2eTest, TestCountMetricWithSameDeactivation);
    FRIEND_TEST(MetricActivationE2eTest, TestCountMetricWithTwoMetricsTwoDeactivations);

    FRIEND_TEST(StatsLogProcessorTest, TestActiveConfigMetricDiskWriteRead);
    FRIEND_TEST(StatsLogProcessorTest, TestActivationOnBoot);
    FRIEND_TEST(StatsLogProcessorTest, TestActivationOnBootMultipleActivations);
    FRIEND_TEST(StatsLogProcessorTest,
            TestActivationOnBootMultipleActivationsDifferentActivationTypes);
    FRIEND_TEST(StatsLogProcessorTest, TestActivationsPersistAcrossSystemServerRestart);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // METRIC_PRODUCER_H
