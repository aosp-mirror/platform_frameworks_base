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

#define DEBUG true
#include "DurationMetricProducer.h"
#include "Log.h"
#include "stats_util.h"

#include <cutils/log.h>
#include <limits.h>
#include <stdlib.h>

using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

DurationMetricProducer::DurationMetricProducer(const DurationMetric& metric,
                                               const int conditionIndex, const size_t startIndex,
                                               const size_t stopIndex, const size_t stopAllIndex,
                                               const sp<ConditionWizard>& wizard)
    // TODO: Pass in the start time from MetricsManager, instead of calling time() here.
    : MetricProducer(time(nullptr) * NANO_SECONDS_IN_A_SECOND, conditionIndex, wizard),
      mMetric(metric),
      mStartIndex(startIndex),
      mStopIndex(stopIndex),
      mStopAllIndex(stopAllIndex) {
    // TODO: The following boiler plate code appears in all MetricProducers, but we can't abstract
    // them in the base class, because the proto generated CountMetric, and DurationMetric are
    // not related. Maybe we should add a template in the future??
    if (metric.has_bucket() && metric.bucket().has_bucket_size_millis()) {
        mBucketSizeNs = metric.bucket().bucket_size_millis() * 1000000;
    } else {
        mBucketSizeNs = LLONG_MAX;
    }

    // TODO: use UidMap if uid->pkg_name is required
    mDimension.insert(mDimension.begin(), metric.dimension().begin(), metric.dimension().end());

    if (metric.links().size() > 0) {
        mConditionLinks.insert(mConditionLinks.begin(), metric.links().begin(),
                               metric.links().end());
        mConditionSliced = true;
    }

    VLOG("metric %lld created. bucket size %lld start_time: %lld", metric.metric_id(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

DurationMetricProducer::~DurationMetricProducer() {
    VLOG("~DurationMetric() called");
}

void DurationMetricProducer::finish() {
    // TODO: write the StatsLogReport to dropbox using
    // DropboxWriter.
}

void DurationMetricProducer::onSlicedConditionMayChange() {
    VLOG("Metric %lld onSlicedConditionMayChange", mMetric.metric_id());
    // Now for each of the on-going event, check if the condition has changed for them.
    for (auto& pair : mCurrentSlicedDuration) {
        VLOG("Metric %lld current %s state: %d", mMetric.metric_id(), pair.first.c_str(),
             pair.second.state);
        if (pair.second.state == kStopped) {
            continue;
        }
        bool conditionMet = mWizard->query(mConditionTrackerIndex, pair.second.conditionKeys) ==
                            ConditionState::kTrue;
        VLOG("key: %s, condition: %d", pair.first.c_str(), conditionMet);
        noteConditionChanged(pair.first, conditionMet, time(nullptr) * 1000000000);
    }
}

void DurationMetricProducer::onConditionChanged(const bool conditionMet) {
    VLOG("Metric %lld onConditionChanged", mMetric.metric_id());
    mCondition = conditionMet;
    // TODO: need to populate the condition change time from the event which triggers the condition
    // change, instead of using current time.
    for (auto& pair : mCurrentSlicedDuration) {
        noteConditionChanged(pair.first, conditionMet, time(nullptr) * 1000000000);
    }
}

static void addDurationBucketsToReport(StatsLogReport_DurationMetricDataWrapper& wrapper,
                                       const vector<KeyValuePair>& key,
                                       const vector<DurationBucketInfo>& buckets) {
    DurationMetricData* data = wrapper.add_data();
    for (const auto& kv : key) {
        data->add_dimension()->CopyFrom(kv);
    }
    for (const auto& bucket : buckets) {
        data->add_bucket_info()->CopyFrom(bucket);
        VLOG("\t bucket [%lld - %lld] count: %lld", bucket.start_bucket_nanos(),
             bucket.end_bucket_nanos(), bucket.duration_nanos());
    }
}

StatsLogReport DurationMetricProducer::onDumpReport() {
    VLOG("metric %lld dump report now...", mMetric.metric_id());
    StatsLogReport report;
    report.set_metric_id(mMetric.metric_id());
    report.set_start_report_nanos(mStartTimeNs);
    // Dump current bucket if it's stale.
    // If current bucket is still on-going, don't force dump current bucket.
    // In finish(), We can force dump current bucket.
    flushDurationIfNeeded(time(nullptr) * NANO_SECONDS_IN_A_SECOND);
    report.set_end_report_nanos(mCurrentBucketStartTimeNs);

    StatsLogReport_DurationMetricDataWrapper* wrapper = report.mutable_duration_metrics();
    for (const auto& pair : mPastBuckets) {
        const HashableDimensionKey& hashableKey = pair.first;
        auto it = mDimensionKeyMap.find(hashableKey);
        if (it == mDimensionKeyMap.end()) {
            ALOGW("Dimension key %s not found?!?! skip...", hashableKey.c_str());
            continue;
        }
        VLOG("  dimension key %s", hashableKey.c_str());
        addDurationBucketsToReport(*wrapper, it->second, pair.second);
    }
    return report;
};

void DurationMetricProducer::onMatchedLogEventInternal(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const map<string, HashableDimensionKey>& conditionKeys, bool condition,
        const LogEvent& event) {
    flushDurationIfNeeded(event.GetTimestampNs());

    if (matcherIndex == mStopAllIndex) {
        noteStopAll(event.GetTimestampNs());
        return;
    }

    if (mCurrentSlicedDuration.find(eventKey) == mCurrentSlicedDuration.end() && mConditionSliced) {
        // add the durationInfo for the current bucket.
        auto& durationInfo = mCurrentSlicedDuration[eventKey];
        durationInfo.conditionKeys = conditionKeys;
    }

    if (matcherIndex == mStartIndex) {
        VLOG("Metric %lld Key: %s Start, Condition %d", mMetric.metric_id(), eventKey.c_str(),
             condition);
        noteStart(eventKey, condition, event.GetTimestampNs());
    } else if (matcherIndex == mStopIndex) {
        VLOG("Metric %lld Key: %s Stop, Condition %d", mMetric.metric_id(), eventKey.c_str(),
             condition);
        noteStop(eventKey, event.GetTimestampNs());
    }
}

void DurationMetricProducer::noteConditionChanged(const HashableDimensionKey& key,
                                                  const bool conditionMet,
                                                  const uint64_t eventTime) {
    flushDurationIfNeeded(eventTime);

    auto it = mCurrentSlicedDuration.find(key);
    if (it == mCurrentSlicedDuration.end()) {
        return;
    }

    switch (it->second.state) {
        case kStarted:
            // if condition becomes false, kStarted -> kPaused. Record the current duration.
            if (!conditionMet) {
                it->second.state = DurationState::kPaused;
                it->second.lastDuration =
                        updateDuration(it->second.lastDuration,
                                       eventTime - it->second.lastStartTime, mMetric.type());
                VLOG("Metric %lld Key: %s Paused because condition is false ", mMetric.metric_id(),
                     key.c_str());
            }
            break;
        case kStopped:
            // nothing to do if it's stopped.
            break;
        case kPaused:
            // if condition becomes true, kPaused -> kStarted. and the start time is the condition
            // change time.
            if (conditionMet) {
                it->second.state = DurationState::kStarted;
                it->second.lastStartTime = eventTime;
                VLOG("Metric %lld Key: %s Paused->Started", mMetric.metric_id(), key.c_str());
            }
            break;
    }
}

void DurationMetricProducer::noteStart(const HashableDimensionKey& key, const bool conditionMet,
                                       const uint64_t eventTime) {
    // this will add an empty bucket for this key if it didn't exist before.
    DurationInfo& duration = mCurrentSlicedDuration[key];

    switch (duration.state) {
        case kStarted:
            // It's safe to do nothing here. even if condition is not true, it means we are about
            // to receive the condition change event.
            break;
        case kPaused:
            // Safe to do nothing here. kPaused is waiting for the condition change.
            break;
        case kStopped:
            if (!conditionMet) {
                // event started, but we need to wait for the condition to become true.
                duration.state = DurationState::kPaused;
                break;
            }
            duration.state = DurationState::kStarted;
            duration.lastStartTime = eventTime;
            break;
    }
}

void DurationMetricProducer::noteStop(const HashableDimensionKey& key, const uint64_t eventTime) {
    if (mCurrentSlicedDuration.find(key) == mCurrentSlicedDuration.end()) {
        // we didn't see a start event before. do nothing.
        return;
    }
    DurationInfo& duration = mCurrentSlicedDuration[key];

    switch (duration.state) {
        case DurationState::kStopped:
            // already stopped, do nothing.
            break;
        case DurationState::kStarted: {
            duration.state = DurationState::kStopped;
            int64_t durationTime = eventTime - duration.lastStartTime;
            VLOG("Metric %lld, key %s, Stop %lld %lld %lld", mMetric.metric_id(), key.c_str(),
                 (long long)duration.lastStartTime, (long long)eventTime, (long long)durationTime);
            duration.lastDuration =
                    updateDuration(duration.lastDuration, durationTime, mMetric.type());
            VLOG("  record duration: %lld ", (long long)duration.lastDuration);
            break;
        }
        case DurationState::kPaused: {
            duration.state = DurationState::kStopped;
            break;
        }
    }
}

int64_t DurationMetricProducer::updateDuration(const int64_t lastDuration,
                                               const int64_t durationTime,
                                               const DurationMetric_AggregationType type) {
    int64_t result = lastDuration;
    switch (type) {
        case DurationMetric_AggregationType_DURATION_SUM:
            result += durationTime;
            break;
        case DurationMetric_AggregationType_DURATION_MAX_SPARSE:
            if (lastDuration < durationTime) {
                result = durationTime;
            }
            break;
        case DurationMetric_AggregationType_DURATION_MIN_SPARSE:
            if (lastDuration > durationTime) {
                result = durationTime;
            }
            break;
    }
    return result;
}

void DurationMetricProducer::noteStopAll(const uint64_t eventTime) {
    for (auto& duration : mCurrentSlicedDuration) {
        noteStop(duration.first, eventTime);
    }
}

// When a new matched event comes in, we check if event falls into the current
// bucket. If not, flush the old counter to past buckets and initialize the current buckt.
void DurationMetricProducer::flushDurationIfNeeded(const uint64_t eventTime) {
    if (mCurrentBucketStartTimeNs + mBucketSizeNs > eventTime) {
        return;
    }

    // adjust the bucket start time
    int numBucketsForward = (eventTime - mCurrentBucketStartTimeNs) / mBucketSizeNs;

    DurationBucketInfo info;
    uint64_t endTime = mCurrentBucketStartTimeNs + mBucketSizeNs;
    info.set_start_bucket_nanos(mCurrentBucketStartTimeNs);
    info.set_end_bucket_nanos(endTime);

    uint64_t oldBucketStartTimeNs = mCurrentBucketStartTimeNs;
    mCurrentBucketStartTimeNs += (numBucketsForward)*mBucketSizeNs;
    VLOG("Metric %lld: new bucket start time: %lld", mMetric.metric_id(),
         (long long)mCurrentBucketStartTimeNs);

    for (auto it = mCurrentSlicedDuration.begin(); it != mCurrentSlicedDuration.end(); ++it) {
        int64_t finalDuration = it->second.lastDuration;
        if (it->second.state == kStarted) {
            // the event is still on-going, duration needs to be updated.
            int64_t durationTime = endTime - it->second.lastStartTime;
            finalDuration = updateDuration(it->second.lastDuration, durationTime, mMetric.type());
        }

        VLOG("  final duration for last bucket: %lld", (long long)finalDuration);

        // Don't record empty bucket.
        if (finalDuration != 0) {
            info.set_duration_nanos(finalDuration);
            // it will auto create new vector of CountbucketInfo if the key is not found.
            auto& bucketList = mPastBuckets[it->first];
            bucketList.push_back(info);
        }

        // if the event is still on-going, add the buckets between previous bucket and now. Because
        // the event has been going on across all the buckets in between.
        // |prev_bucket|...|..|...|now_bucket|
        if (it->second.state == kStarted) {
            for (int i = 1; i < numBucketsForward; i++) {
                DurationBucketInfo info;
                info.set_start_bucket_nanos(oldBucketStartTimeNs + mBucketSizeNs * i);
                info.set_end_bucket_nanos(endTime + mBucketSizeNs * i);
                info.set_duration_nanos(mBucketSizeNs);
                auto& bucketList = mPastBuckets[it->first];
                bucketList.push_back(info);
                VLOG("  add filling bucket with duration %lld", (long long)mBucketSizeNs);
            }
        }

        if (it->second.state == DurationState::kStopped) {
            // No need to keep buckets for events that were stopped before. If the event starts
            // again, we will add it back.
            mCurrentSlicedDuration.erase(it);
        } else {
            // for kPaused, and kStarted event, we will keep the buckets, and reset the start time
            // and duration.
            it->second.lastStartTime = mCurrentBucketStartTimeNs;
            it->second.lastDuration = 0;
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
