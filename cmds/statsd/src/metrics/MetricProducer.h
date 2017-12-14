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

#include "anomaly/AnomalyTracker.h"
#include "condition/ConditionWizard.h"
#include "config/ConfigKey.h"
#include "matchers/matcher_util.h"
#include "packages/PackageInfoListener.h"

#include <log/logprint.h>
#include <utils/RefBase.h>

namespace android {
namespace os {
namespace statsd {

// A MetricProducer is responsible for compute one single metrics, creating stats log report, and
// writing the report to dropbox. MetricProducers should respond to package changes as required in
// PackageInfoListener, but if none of the metrics are slicing by package name, then the update can
// be a no-op.
class MetricProducer : public virtual PackageInfoListener {
public:
    MetricProducer(const std::string& name, const ConfigKey& key, const int64_t startTimeNs,
                   const int conditionIndex, const sp<ConditionWizard>& wizard)
        : mName(name),
          mConfigKey(key),
          mStartTimeNs(startTimeNs),
          mCurrentBucketStartTimeNs(startTimeNs),
          mCurrentBucketNum(0),
          mCondition(conditionIndex >= 0 ? false : true),
          mConditionSliced(false),
          mWizard(wizard),
          mConditionTrackerIndex(conditionIndex) {
        // reuse the same map for non-sliced metrics too. this way, we avoid too many if-else.
        mDimensionKeyMap[DEFAULT_DIMENSION_KEY] = std::vector<KeyValuePair>();
    };
    virtual ~MetricProducer(){};

    // Consume the parsed stats log entry that already matched the "what" of the metric.
    void onMatchedLogEvent(const size_t matcherIndex, const LogEvent& event) {
        std::lock_guard<std::mutex> lock(mMutex);
        onMatchedLogEventLocked(matcherIndex, event);
    }

    void onConditionChanged(const bool condition, const uint64_t eventTime) {
        std::lock_guard<std::mutex> lock(mMutex);
        onConditionChangedLocked(condition, eventTime);
    }

    void onSlicedConditionMayChange(const uint64_t eventTime) {
        std::lock_guard<std::mutex> lock(mMutex);
        onSlicedConditionMayChangeLocked(eventTime);
    }

    bool isConditionSliced() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mConditionSliced;
    };

    // Output the metrics data to [protoOutput]. All metrics reports end with the same timestamp.
    void onDumpReport(const uint64_t dumpTimeNs, android::util::ProtoOutputStream* protoOutput) {
        std::lock_guard<std::mutex> lock(mMutex);
        return onDumpReportLocked(dumpTimeNs, protoOutput);
    }

    // Returns the memory in bytes currently used to store this metric's data. Does not change
    // state.
    size_t byteSize() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return byteSizeLocked();
    }

    virtual sp<AnomalyTracker> createAnomalyTracker(const Alert &alert) {
        return new AnomalyTracker(alert, mConfigKey);
    }

    void addAnomalyTracker(sp<AnomalyTracker> tracker) {
        std::lock_guard<std::mutex> lock(mMutex);
        mAnomalyTrackers.push_back(tracker);
    }

    int64_t getBuckeSizeInNs() const {
        std::lock_guard<std::mutex> lock(mMutex);
        return mBucketSizeNs;
    }

protected:
    virtual void onConditionChangedLocked(const bool condition, const uint64_t eventTime) = 0;
    virtual void onSlicedConditionMayChangeLocked(const uint64_t eventTime) = 0;
    virtual void onDumpReportLocked(const uint64_t dumpTimeNs,
                                    android::util::ProtoOutputStream* protoOutput) = 0;
    virtual size_t byteSizeLocked() const = 0;

    const std::string mName;

    const ConfigKey mConfigKey;

    // The start time for the current in memory metrics data.
    uint64_t mStartTimeNs;

    uint64_t mCurrentBucketStartTimeNs;

    uint64_t mCurrentBucketNum;

    int64_t mBucketSizeNs;

    bool mCondition;

    bool mConditionSliced;

    sp<ConditionWizard> mWizard;

    int mConditionTrackerIndex;

    std::vector<KeyMatcher> mDimension;  // The dimension defined in statsd_config

    // Keep the map from the internal HashableDimensionKey to std::vector<KeyValuePair>
    // that StatsLogReport wants.
    std::unordered_map<HashableDimensionKey, std::vector<KeyValuePair>> mDimensionKeyMap;

    std::vector<MetricConditionLink> mConditionLinks;

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
            const size_t matcherIndex, const HashableDimensionKey& eventKey,
            const std::map<std::string, HashableDimensionKey>& conditionKey, bool condition,
            const LogEvent& event) = 0;

    // Consume the parsed stats log entry that already matched the "what" of the metric.
    void onMatchedLogEventLocked(const size_t matcherIndex, const LogEvent& event);

    mutable std::mutex mMutex;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // METRIC_PRODUCER_H
