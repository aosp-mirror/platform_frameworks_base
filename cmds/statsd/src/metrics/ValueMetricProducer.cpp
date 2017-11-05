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

#define DEBUG true  // STOPSHIP if true
#include "Log.h"

#include "ValueMetricProducer.h"

#include <cutils/log.h>
#include <limits.h>
#include <stdlib.h>

using std::map;
using std::unordered_map;
using std::list;
using std::make_shared;
using std::shared_ptr;
using std::unique_ptr;

namespace android {
namespace os {
namespace statsd {

// ValueMetric has a minimum bucket size of 10min so that we don't pull too frequently
ValueMetricProducer::ValueMetricProducer(const ValueMetric& metric, const int conditionIndex,
                                         const sp<ConditionWizard>& wizard, const int pullTagId)
    : MetricProducer((time(nullptr) / 600 * 600 * NANO_SECONDS_IN_A_SECOND), conditionIndex,
                     wizard),
      mMetric(metric),
      mPullTagId(pullTagId) {
  // TODO: valuemetric for pushed events may need unlimited bucket length
  mBucketSizeNs = mMetric.bucket().bucket_size_millis() * 1000 * 1000;

  mDimension.insert(mDimension.begin(), metric.dimension().begin(), metric.dimension().end());

  if (metric.links().size() > 0) {
    mConditionLinks.insert(mConditionLinks.begin(), metric.links().begin(),
                           metric.links().end());
    mConditionSliced = true;
  }

  if (!metric.has_condition() && mPullTagId != -1) {
    mStatsPullerManager.RegisterReceiver(mPullTagId, this, metric.bucket().bucket_size_millis());
  }

  VLOG("value metric %lld created. bucket size %lld start_time: %lld", metric.metric_id(),
       (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

ValueMetricProducer::~ValueMetricProducer() {
  VLOG("~ValueMetricProducer() called");
}

void ValueMetricProducer::finish() {
  // TODO: write the StatsLogReport to dropbox using
  // DropboxWriter.
}

static void addSlicedCounterToReport(StatsLogReport_ValueMetricDataWrapper& wrapper,
                                     const vector<KeyValuePair>& key,
                                     const vector<ValueBucketInfo>& buckets) {
  ValueMetricData* data = wrapper.add_data();
  for (const auto& kv : key) {
    data->add_dimension()->CopyFrom(kv);
  }
  for (const auto& bucket : buckets) {
    data->add_bucket_info()->CopyFrom(bucket);
    VLOG("\t bucket [%lld - %lld] value: %lld", bucket.start_bucket_nanos(),
         bucket.end_bucket_nanos(), bucket.value());
  }
}

void ValueMetricProducer::onSlicedConditionMayChange(const uint64_t eventTime) {
    VLOG("Metric %lld onSlicedConditionMayChange", mMetric.metric_id());
}

StatsLogReport ValueMetricProducer::onDumpReport() {
  VLOG("metric %lld dump report now...", mMetric.metric_id());

  StatsLogReport report;
  report.set_metric_id(mMetric.metric_id());
  report.set_start_report_nanos(mStartTimeNs);

  // Dump current bucket if it's stale.
  // If current bucket is still on-going, don't force dump current bucket.
  // In finish(), We can force dump current bucket.
  //    flush_if_needed(time(nullptr) * NANO_SECONDS_IN_A_SECOND);
  report.set_end_report_nanos(mCurrentBucketStartTimeNs);

  StatsLogReport_ValueMetricDataWrapper* wrapper = report.mutable_value_metrics();

  for (const auto& pair : mPastBuckets) {
    const HashableDimensionKey& hashableKey = pair.first;
    auto it = mDimensionKeyMap.find(hashableKey);
    if (it == mDimensionKeyMap.end()) {
      ALOGE("Dimension key %s not found?!?! skip...", hashableKey.c_str());
      continue;
    }

    VLOG("  dimension key %s", hashableKey.c_str());
    addSlicedCounterToReport(*wrapper, it->second, pair.second);
  }
  return report;
  // TODO: Clear mPastBuckets, mDimensionKeyMap once the report is dumped.
}

void ValueMetricProducer::onConditionChanged(const bool condition, const uint64_t eventTime) {
    mCondition = condition;

    if (mPullTagId != -1) {
        if (mCondition == true) {
            mStatsPullerManager.RegisterReceiver(mPullTagId, this,
                                                 mMetric.bucket().bucket_size_millis());
        } else if (mCondition == ConditionState::kFalse) {
            mStatsPullerManager.UnRegisterReceiver(mPullTagId, this);
        }

        vector<shared_ptr<LogEvent>> allData;
        if (mStatsPullerManager.Pull(mPullTagId, &allData)) {
            if (allData.size() == 0) {
                return;
            }
            AutoMutex _l(mLock);
            for (const auto& data : allData) {
                onMatchedLogEvent(0, *data, false);
            }
            flush_if_needed(eventTime);
        }
        return;
    }
}

void ValueMetricProducer::onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& allData) {
    if (mCondition == ConditionState::kTrue || !mMetric.has_condition()) {
        AutoMutex _l(mLock);
        if (allData.size() == 0) {
            return;
        }
        uint64_t eventTime = allData.at(0)->GetTimestampNs();
        for (const auto& data : allData) {
            onMatchedLogEvent(0, *data, true);
        }
        flush_if_needed(eventTime);
    }
}

void ValueMetricProducer::onMatchedLogEventInternal(
    const size_t matcherIndex, const HashableDimensionKey& eventKey,
    const map<string, HashableDimensionKey>& conditionKey, bool condition,
    const LogEvent& event, bool scheduledPull) {
  uint64_t eventTimeNs = event.GetTimestampNs();
  if (eventTimeNs < mCurrentBucketStartTimeNs) {
      VLOG("Skip event due to late arrival: %lld vs %lld", (long long)eventTimeNs,
           (long long)mCurrentBucketStartTimeNs);
      return;
  }

  Interval& interval = mCurrentSlicedBucket[eventKey];

  long value = get_value(event);

  if (scheduledPull) {
    if (interval.raw.size() > 0) {
      interval.raw.back().second = value;
    } else {
      interval.raw.push_back(std::make_pair(value, value));
    }
    mNextSlicedBucket[eventKey].raw[0].first = value;
  } else {
    if (mCondition == ConditionState::kTrue) {
      interval.raw.push_back(std::make_pair(value, 0));
    } else {
      if (interval.raw.size() != 0) {
        interval.raw.back().second = value;
      }
    }
  }
  if (mPullTagId == -1) {
      flush_if_needed(eventTimeNs);
  }
}

long ValueMetricProducer::get_value(const LogEvent& event) {
  status_t err = NO_ERROR;
  long val = event.GetLong(mMetric.value_field(), &err);
  if (err == NO_ERROR) {
    return val;
  } else {
    VLOG("Can't find value in message.");
    return 0;
  }
}

void ValueMetricProducer::flush_if_needed(const uint64_t eventTimeNs) {
    if (mCurrentBucketStartTimeNs + mBucketSizeNs > eventTimeNs) {
        VLOG("eventTime is %lld, less than next bucket start time %lld", (long long)eventTimeNs,
             (long long)(mCurrentBucketStartTimeNs + mBucketSizeNs));
        return;
    }

    VLOG("finalizing bucket for %ld, dumping %d slices", (long)mCurrentBucketStartTimeNs,
         (int)mCurrentSlicedBucket.size());
    ValueBucketInfo info;
    info.set_start_bucket_nanos(mCurrentBucketStartTimeNs);
    info.set_end_bucket_nanos(mCurrentBucketStartTimeNs + mBucketSizeNs);

    for (const auto& slice : mCurrentSlicedBucket) {
      long value = 0;
      for (const auto& pair : slice.second.raw) {
        value += pair.second - pair.first;
      }
      info.set_value(value);
      VLOG(" %s, %ld", slice.first.c_str(), value);
      // it will auto create new vector of ValuebucketInfo if the key is not found.
      auto& bucketList = mPastBuckets[slice.first];
      bucketList.push_back(info);
    }

    // Reset counters
    mCurrentSlicedBucket.swap(mNextSlicedBucket);
    mNextSlicedBucket.clear();
    int64_t numBucketsForward = (eventTimeNs - mCurrentBucketStartTimeNs) / mBucketSizeNs;
    if (numBucketsForward >1) {
        VLOG("Skipping forward %lld buckets", (long long)numBucketsForward);
    }
    mCurrentBucketStartTimeNs = mCurrentBucketStartTimeNs + numBucketsForward * mBucketSizeNs;
    VLOG("metric %lld: new bucket start time: %lld", mMetric.metric_id(),
         (long long)mCurrentBucketStartTimeNs);
}

}  // namespace statsd
}  // namespace os
}  // namespace android