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

#include <frameworks/base/cmds/statsd/src/active_config_list.pb.h>
#include <utils/RefBase.h>

#include <unordered_map>

#include "HashableDimensionKey.h"
#include "anomaly/AnomalyTracker.h"
#include "condition/ConditionWizard.h"
#include "config/ConfigKey.h"
#include "matchers/matcher_util.h"
#include "packages/PackageInfoListener.h"
#include "state/StateListener.h"
#include "state/StateManager.h"

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

// Keep this in sync with BucketDropReason enum in stats_log.proto
enum BucketDropReason {
    // For ValueMetric, a bucket is dropped during a dump report request iff
    // current bucket should be included, a pull is needed (pulled metric and
    // condition is true), and we are under fast time constraints.
    DUMP_REPORT_REQUESTED = 1,
    EVENT_IN_WRONG_BUCKET = 2,
    CONDITION_UNKNOWN = 3,
    PULL_FAILED = 4,
    PULL_DELAYED = 5,
    DIMENSION_GUARDRAIL_REACHED = 6,
    MULTIPLE_BUCKETS_SKIPPED = 7,
    // Not an invalid bucket case, but the bucket is dropped.
    BUCKET_TOO_SMALL = 8
};

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

struct DropEvent {
    // Reason for dropping the bucket and/or marking the bucket invalid.
    BucketDropReason reason;
    // The timestamp of the drop event.
    int64_t dropTimeNs;
};

struct SkippedBucket {
    // Start time of the dropped bucket.
    int64_t bucketStartTimeNs;
    // End time of the dropped bucket.
    int64_t bucketEndTimeNs;
    // List of events that invalidated this bucket.
    std::vector<DropEvent> dropEvents;

    void reset() {
        bucketStartTimeNs = 0;
        bucketEndTimeNs = 0;
        dropEvents.clear();
    }
};

// A MetricProducer is responsible for compute one single metrics, creating stats log report, and
// writing the report to dropbox. MetricProducers should respond to package changes as required in
// PackageInfoListener, but if none of the metrics are slicing by package name, then the update can
// be a no-op.
class MetricProducer : public virtual android::RefBase, public virtual StateListener {
public:
    MetricProducer(const int64_t& metricId, const ConfigKey& key, const int64_t timeBaseNs,
                   const int conditionIndex, const sp<ConditionWizard>& wizard,
                   const std::unordered_map<int, std::shared_ptr<Activation>>& eventActivationMap,
                   const std::unordered_map<int, std::vector<std::shared_ptr<Activation>>>&
                           eventDeactivationMap,
                   const vector<int>& slicedStateAtoms,
                   const unordered_map<int, unordered_map<int, int64_t>>& stateGroupMap);

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
    virtual void notifyAppUpgrade(const int64_t& eventTimeNs, const string& apk, const int uid,
                          const int64_t version) {
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

    void notifyAppRemoved(const int64_t& eventTimeNs, const string& apk, const int uid) {
        // Force buckets to split on removal also.
        notifyAppUpgrade(eventTimeNs, apk, uid, 0);
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

    void onStateChanged(const int64_t eventTimeNs, const int32_t atomId,
                        const HashableDimensionKey& primaryKey, int oldState, int newState){};

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

    void prepareFirstBucket() {
        std::lock_guard<std::mutex> lock(mMutex);
        prepareFirstBucketLocked();
    }

    // Returns the memory in bytes currently used to store this metric's data. Does not change
    // state.
    size_t byteSize() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return byteSizeLocked();
    }

    void dumpStates(FILE* out, bool verbose) const {
        std::lock_guard<std::mutex> lock(mMutex);
        dumpStatesLocked(out, verbose);
    }

    // Let MetricProducer drop in-memory data to save memory.
    // We still need to keep future data valid and anomaly tracking work, which means we will
    // have to flush old data, informing anomaly trackers then safely drop old data.
    // We still keep current bucket data for future metrics' validity.
    void dropData(const int64_t dropTimeNs) {
        std::lock_guard<std::mutex> lock(mMutex);
        dropDataLocked(dropTimeNs);
    }

    void loadActiveMetric(const ActiveMetric& activeMetric, int64_t currentTimeNs) {
        std::lock_guard<std::mutex> lock(mMutex);
        loadActiveMetricLocked(activeMetric, currentTimeNs);
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

    void flushIfExpire(int64_t elapsedTimestampNs);

    void writeActiveMetricToProtoOutputStream(
            int64_t currentTimeNs, const DumpReportReason reason, ProtoOutputStream* proto);

    // Start: getters/setters
    inline const int64_t& getMetricId() const {
        return mMetricId;
    }

    // For test only.
    inline int64_t getCurrentBucketNum() const {
        return mCurrentBucketNum;
    }

    int64_t getBucketSizeInNs() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mBucketSizeNs;
    }

    inline const std::vector<int> getSlicedStateAtoms() {
        std::lock_guard<std::mutex> lock(mMutex);
        return mSlicedStateAtoms;
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
    // End: getters/setters
protected:
    /**
     * Flushes the current bucket if the eventTime is after the current bucket's end time. This will
       also flush the current partial bucket in memory.
     */
    virtual void flushIfNeededLocked(const int64_t& eventTime){};

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

    /**
     * Flushes all the data including the current partial bucket.
     */
    virtual void flushLocked(const int64_t& eventTimeNs) {
        flushIfNeededLocked(eventTimeNs);
        flushCurrentBucketLocked(eventTimeNs, eventTimeNs);
    };

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
            const ConditionKey& conditionKey, bool condition, const LogEvent& event,
            const map<int, HashableDimensionKey>& statePrimaryKeys) = 0;

    // Consume the parsed stats log entry that already matched the "what" of the metric.
    virtual void onMatchedLogEventLocked(const size_t matcherIndex, const LogEvent& event);
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
    virtual void prepareFirstBucketLocked(){};
    virtual size_t byteSizeLocked() const = 0;
    virtual void dumpStatesLocked(FILE* out, bool verbose) const = 0;
    virtual void dropDataLocked(const int64_t dropTimeNs) = 0;
    void loadActiveMetricLocked(const ActiveMetric& activeMetric, int64_t currentTimeNs);
    void activateLocked(int activationTrackerIndex, int64_t elapsedTimestampNs);
    void cancelEventActivationLocked(int deactivationTrackerIndex);

    bool evaluateActiveStateLocked(int64_t elapsedTimestampNs);

    virtual void onActiveStateChangedLocked(const int64_t& eventTimeNs) {
        if (!mIsActive) {
            flushLocked(eventTimeNs);
        }
    }

    inline bool isActiveLocked() const {
        return mIsActive;
    }

    // Convenience to compute the current bucket's end time, which is always aligned with the
    // start time of the metric.
    int64_t getCurrentBucketEndTimeNs() const {
        return mTimeBaseNs + (mCurrentBucketNum + 1) * mBucketSizeNs;
    }

    int64_t getBucketNumFromEndTimeNs(const int64_t endNs) {
        return (endNs - mTimeBaseNs) / mBucketSizeNs - 1;
    }

    // Query StateManager for original state value.
    // If no state map exists for this atom, return the original value.
    // Otherwise, return the group_id mapped to the atom and original value.
    void getMappedStateValue(const int32_t atomId, const HashableDimensionKey& queryKey,
                             FieldValue* value);

    DropEvent buildDropEvent(const int64_t dropTimeNs, const BucketDropReason reason);

    // Returns true if the number of drop events in the current bucket has
    // exceeded the maximum number allowed, which is currently capped at 10.
    bool maxDropEventsReached();

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

    int mConditionTrackerIndex;

    bool mConditionSliced;

    sp<ConditionWizard> mWizard;

    bool mContainANYPositionInDimensionsInWhat;

    bool mSliceByPositionALL;

    vector<Matcher> mDimensionsInWhat;  // The dimensions_in_what defined in statsd_config

    // True iff the metric to condition links cover all dimension fields in the condition tracker.
    // This field is always false for combinational condition trackers.
    bool mHasLinksToAllConditionDimensionsInTracker;

    std::vector<Metric2Condition> mMetric2ConditionLinks;

    std::vector<sp<AnomalyTracker>> mAnomalyTrackers;

    mutable std::mutex mMutex;

    // When the metric producer has multiple activations, these activations are ORed to determine
    // whether the metric producer is ready to generate metrics.
    std::unordered_map<int, std::shared_ptr<Activation>> mEventActivationMap;

    // Maps index of atom matcher for deactivation to a list of Activation structs.
    std::unordered_map<int, std::vector<std::shared_ptr<Activation>>> mEventDeactivationMap;

    bool mIsActive;

    // The slice_by_state atom ids defined in statsd_config.
    std::vector<int32_t> mSlicedStateAtoms;

    // Maps atom ids and state values to group_ids (<atom_id, <value, group_id>>).
    std::unordered_map<int32_t, std::unordered_map<int, int64_t>> mStateGroupMap;

    // MetricStateLinks defined in statsd_config that link fields in the state
    // atom to fields in the "what" atom.
    std::vector<Metric2State> mMetric2StateLinks;

    SkippedBucket mCurrentSkippedBucket;
    // Buckets that were invalidated and had their data dropped.
    std::vector<SkippedBucket> mSkippedBuckets;

    FRIEND_TEST(CountMetricE2eTest, TestSlicedState);
    FRIEND_TEST(CountMetricE2eTest, TestSlicedStateWithMap);
    FRIEND_TEST(CountMetricE2eTest, TestMultipleSlicedStates);
    FRIEND_TEST(CountMetricE2eTest, TestSlicedStateWithPrimaryFields);

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

    FRIEND_TEST(ValueMetricE2eTest, TestInitWithSlicedState);
    FRIEND_TEST(ValueMetricE2eTest, TestInitWithSlicedState_WithDimensions);
    FRIEND_TEST(ValueMetricE2eTest, TestInitWithSlicedState_WithIncorrectDimensions);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // METRIC_PRODUCER_H
