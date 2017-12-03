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
#include "guardrail/StatsdStats.h"

#include <cutils/log.h>
#include <limits.h>
#include <stdlib.h>

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_BOOL;
using android::util::FIELD_TYPE_FLOAT;
using android::util::FIELD_TYPE_INT32;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::FIELD_TYPE_STRING;
using android::util::ProtoOutputStream;
using std::list;
using std::make_pair;
using std::make_shared;
using std::map;
using std::shared_ptr;
using std::unique_ptr;
using std::unordered_map;

namespace android {
namespace os {
namespace statsd {

// for StatsLogReport
const int FIELD_ID_NAME = 1;
const int FIELD_ID_START_REPORT_NANOS = 2;
const int FIELD_ID_END_REPORT_NANOS = 3;
const int FIELD_ID_VALUE_METRICS = 7;
// for ValueMetricDataWrapper
const int FIELD_ID_DATA = 1;
// for ValueMetricData
const int FIELD_ID_DIMENSION = 1;
const int FIELD_ID_BUCKET_INFO = 2;
// for KeyValuePair
const int FIELD_ID_KEY = 1;
const int FIELD_ID_VALUE_STR = 2;
const int FIELD_ID_VALUE_INT = 3;
const int FIELD_ID_VALUE_BOOL = 4;
const int FIELD_ID_VALUE_FLOAT = 5;
// for ValueBucketInfo
const int FIELD_ID_START_BUCKET_NANOS = 1;
const int FIELD_ID_END_BUCKET_NANOS = 2;
const int FIELD_ID_VALUE = 3;

static const uint64_t kDefaultBucketSizeMillis = 60 * 60 * 1000L;

// ValueMetric has a minimum bucket size of 10min so that we don't pull too frequently
ValueMetricProducer::ValueMetricProducer(const ConfigKey& key, const ValueMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard, const int pullTagId,
                                         const uint64_t startTimeNs,
                                         shared_ptr<StatsPullerManager> statsPullerManager)
    : MetricProducer(key, startTimeNs, conditionIndex, wizard),
      mMetric(metric),
      mStatsPullerManager(statsPullerManager),
      mPullTagId(pullTagId) {
    // TODO: valuemetric for pushed events may need unlimited bucket length
    if (metric.has_bucket() && metric.bucket().has_bucket_size_millis()) {
        mBucketSizeNs = mMetric.bucket().bucket_size_millis() * 1000 * 1000;
    } else {
        mBucketSizeNs = kDefaultBucketSizeMillis * 1000 * 1000;
    }

    mDimension.insert(mDimension.begin(), metric.dimension().begin(), metric.dimension().end());

    if (metric.links().size() > 0) {
        mConditionLinks.insert(mConditionLinks.begin(), metric.links().begin(),
                               metric.links().end());
        mConditionSliced = true;
    }

    if (!metric.has_condition() && mPullTagId != -1) {
        VLOG("Setting up periodic pulling for %d", mPullTagId);
        mStatsPullerManager->RegisterReceiver(mPullTagId, this,
                                              metric.bucket().bucket_size_millis());
    }

    startNewProtoOutputStreamLocked(mStartTimeNs);

    VLOG("value metric %s created. bucket size %lld start_time: %lld", metric.name().c_str(),
         (long long)mBucketSizeNs, (long long)mStartTimeNs);
}

// for testing
ValueMetricProducer::ValueMetricProducer(const ConfigKey& key, const ValueMetric& metric,
                                         const int conditionIndex,
                                         const sp<ConditionWizard>& wizard, const int pullTagId,
                                         const uint64_t startTimeNs)
    : ValueMetricProducer(key, metric, conditionIndex, wizard, pullTagId, startTimeNs,
                          make_shared<StatsPullerManager>()) {
}

ValueMetricProducer::~ValueMetricProducer() {
    VLOG("~ValueMetricProducer() called");
    if (mPullTagId != -1) {
        mStatsPullerManager->UnRegisterReceiver(mPullTagId, this);
    }
}

void ValueMetricProducer::startNewProtoOutputStreamLocked(long long startTime) {
    mProto = std::make_unique<ProtoOutputStream>();
    mProto->write(FIELD_TYPE_STRING | FIELD_ID_NAME, mMetric.name());
    mProto->write(FIELD_TYPE_INT64 | FIELD_ID_START_REPORT_NANOS, startTime);
    mProtoToken = mProto->start(FIELD_TYPE_MESSAGE | FIELD_ID_VALUE_METRICS);
}

void ValueMetricProducer::finish() {
    // TODO: write the StatsLogReport to dropbox using
    // DropboxWriter.
}

void ValueMetricProducer::onSlicedConditionMayChangeLocked(const uint64_t eventTime) {
    VLOG("Metric %s onSlicedConditionMayChange", mMetric.name().c_str());
}

std::unique_ptr<std::vector<uint8_t>> ValueMetricProducer::onDumpReportLocked() {
    VLOG("metric %s dump report now...", mMetric.name().c_str());

    for (const auto& pair : mPastBuckets) {
        const HashableDimensionKey& hashableKey = pair.first;
        VLOG("  dimension key %s", hashableKey.c_str());
        auto it = mDimensionKeyMap.find(hashableKey);
        if (it == mDimensionKeyMap.end()) {
            ALOGE("Dimension key %s not found?!?! skip...", hashableKey.c_str());
            continue;
        }
        long long wrapperToken =
                mProto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DATA);

        // First fill dimension (KeyValuePairs).
        for (const auto& kv : it->second) {
            long long dimensionToken =
                    mProto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_DIMENSION);
            mProto->write(FIELD_TYPE_INT32 | FIELD_ID_KEY, kv.key());
            if (kv.has_value_str()) {
                mProto->write(FIELD_TYPE_STRING | FIELD_ID_VALUE_STR, kv.value_str());
            } else if (kv.has_value_int()) {
                mProto->write(FIELD_TYPE_INT64 | FIELD_ID_VALUE_INT, kv.value_int());
            } else if (kv.has_value_bool()) {
                mProto->write(FIELD_TYPE_BOOL | FIELD_ID_VALUE_BOOL, kv.value_bool());
            } else if (kv.has_value_float()) {
                mProto->write(FIELD_TYPE_FLOAT | FIELD_ID_VALUE_FLOAT, kv.value_float());
            }
            mProto->end(dimensionToken);
        }

        // Then fill bucket_info (ValueBucketInfo).
        for (const auto& bucket : pair.second) {
            long long bucketInfoToken =
                    mProto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_BUCKET_INFO);
            mProto->write(FIELD_TYPE_INT64 | FIELD_ID_START_BUCKET_NANOS,
                          (long long)bucket.mBucketStartNs);
            mProto->write(FIELD_TYPE_INT64 | FIELD_ID_END_BUCKET_NANOS,
                          (long long)bucket.mBucketEndNs);
            mProto->write(FIELD_TYPE_INT64 | FIELD_ID_VALUE, (long long)bucket.mValue);
            mProto->end(bucketInfoToken);
            VLOG("\t bucket [%lld - %lld] count: %lld", (long long)bucket.mBucketStartNs,
                 (long long)bucket.mBucketEndNs, (long long)bucket.mValue);
        }
        mProto->end(wrapperToken);
    }
    mProto->end(mProtoToken);
    mProto->write(FIELD_TYPE_INT64 | FIELD_ID_END_REPORT_NANOS,
                  (long long)mCurrentBucketStartTimeNs);

    VLOG("metric %s dump report now...", mMetric.name().c_str());
    std::unique_ptr<std::vector<uint8_t>> buffer = serializeProtoLocked();

    startNewProtoOutputStreamLocked(time(nullptr) * NS_PER_SEC);
    mPastBuckets.clear();

    return buffer;

    // TODO: Clear mDimensionKeyMap once the report is dumped.
}

void ValueMetricProducer::onConditionChangedLocked(const bool condition, const uint64_t eventTime) {
    mCondition = condition;

    if (mPullTagId != -1) {
        if (mCondition == true) {
            mStatsPullerManager->RegisterReceiver(mPullTagId, this,
                                                  mMetric.bucket().bucket_size_millis());
        } else if (mCondition == false) {
            mStatsPullerManager->UnRegisterReceiver(mPullTagId, this);
        }

        vector<shared_ptr<LogEvent>> allData;
        if (mStatsPullerManager->Pull(mPullTagId, &allData)) {
            if (allData.size() == 0) {
                return;
            }
            for (const auto& data : allData) {
                onMatchedLogEventLocked(0, *data, false);
            }
            flushIfNeededLocked(eventTime);
        }
        return;
    }
}

void ValueMetricProducer::onDataPulled(const std::vector<std::shared_ptr<LogEvent>>& allData) {
    std::lock_guard<std::mutex> lock(mMutex);

    if (mCondition == true || !mMetric.has_condition()) {
        if (allData.size() == 0) {
            return;
        }
        uint64_t eventTime = allData.at(0)->GetTimestampNs();
        // alarm is not accurate and might drift.
        if (eventTime > mCurrentBucketStartTimeNs + mBucketSizeNs * 3 / 2) {
            flushIfNeededLocked(eventTime);
        }
        for (const auto& data : allData) {
            onMatchedLogEventLocked(0, *data, true);
        }
        flushIfNeededLocked(eventTime);
    }
}

bool ValueMetricProducer::hitGuardRailLocked(const HashableDimensionKey& newKey) {
    // ===========GuardRail==============
    // 1. Report the tuple count if the tuple count > soft limit
    if (mCurrentSlicedBucket.find(newKey) != mCurrentSlicedBucket.end()) {
        return false;
    }
    if (mCurrentSlicedBucket.size() > StatsdStats::kDimensionKeySizeSoftLimit - 1) {
        size_t newTupleCount = mCurrentSlicedBucket.size() + 1;
        StatsdStats::getInstance().noteMetricDimensionSize(mConfigKey, mMetric.name(),
                                                           newTupleCount);
        // 2. Don't add more tuples, we are above the allowed threshold. Drop the data.
        if (newTupleCount > StatsdStats::kDimensionKeySizeHardLimit) {
            ALOGE("ValueMetric %s dropping data for dimension key %s", mMetric.name().c_str(),
                  newKey.c_str());
            return true;
        }
    }

    return false;
}

void ValueMetricProducer::onMatchedLogEventInternalLocked(
        const size_t matcherIndex, const HashableDimensionKey& eventKey,
        const map<string, HashableDimensionKey>& conditionKey, bool condition,
        const LogEvent& event, bool scheduledPull) {
    uint64_t eventTimeNs = event.GetTimestampNs();
    if (eventTimeNs < mCurrentBucketStartTimeNs) {
        VLOG("Skip event due to late arrival: %lld vs %lld", (long long)eventTimeNs,
             (long long)mCurrentBucketStartTimeNs);
        return;
    }

    if (hitGuardRailLocked(eventKey)) {
        return;
    }
    Interval& interval = mCurrentSlicedBucket[eventKey];

    long value = get_value(event);

    if (mPullTagId != -1) {
        if (scheduledPull) {
            // scheduled pull always sets beginning of current bucket and end
            // of next bucket
            if (interval.raw.size() > 0) {
                interval.raw.back().second = value;
            } else {
                interval.raw.push_back(make_pair(value, value));
            }
            Interval& nextInterval = mNextSlicedBucket[eventKey];
            if (nextInterval.raw.size() == 0) {
                nextInterval.raw.push_back(make_pair(value, 0));
            } else {
                nextInterval.raw.front().first = value;
            }
        } else {
            if (mCondition == true) {
                interval.raw.push_back(make_pair(value, 0));
            } else {
                if (interval.raw.size() != 0) {
                    interval.raw.back().second = value;
                } else {
                    interval.tainted = true;
                    VLOG("Data on condition true missing!");
                }
            }
        }
    } else {
        flushIfNeededLocked(eventTimeNs);
        interval.raw.push_back(make_pair(value, 0));
    }
}

long ValueMetricProducer::get_value(const LogEvent& event) {
    status_t err = NO_ERROR;
    long val = event.GetLong(mMetric.value_field(), &err);
    if (err == NO_ERROR) {
        return val;
    } else {
        VLOG("Can't find value in message. %s", event.ToString().c_str());
        return 0;
    }
}

void ValueMetricProducer::flushIfNeededLocked(const uint64_t& eventTimeNs) {
    if (mCurrentBucketStartTimeNs + mBucketSizeNs > eventTimeNs) {
        VLOG("eventTime is %lld, less than next bucket start time %lld", (long long)eventTimeNs,
             (long long)(mCurrentBucketStartTimeNs + mBucketSizeNs));
        return;
    }
    VLOG("finalizing bucket for %ld, dumping %d slices", (long)mCurrentBucketStartTimeNs,
         (int)mCurrentSlicedBucket.size());
    ValueBucket info;
    info.mBucketStartNs = mCurrentBucketStartTimeNs;
    info.mBucketEndNs = mCurrentBucketStartTimeNs + mBucketSizeNs;
    info.mBucketNum = mCurrentBucketNum;

    int tainted = 0;
    for (const auto& slice : mCurrentSlicedBucket) {
        long value = 0;
        if (mPullTagId != -1) {
            for (const auto& pair : slice.second.raw) {
                value += (pair.second - pair.first);
            }
        } else {
            for (const auto& pair : slice.second.raw) {
                value += pair.first;
            }
        }
        tainted += slice.second.tainted;
        info.mValue = value;
        VLOG(" %s, %ld, %d", slice.first.c_str(), value, tainted);
        // it will auto create new vector of ValuebucketInfo if the key is not found.
        auto& bucketList = mPastBuckets[slice.first];
        bucketList.push_back(info);
    }

    // Reset counters
    mCurrentSlicedBucket.swap(mNextSlicedBucket);
    mNextSlicedBucket.clear();

    int64_t numBucketsForward = (eventTimeNs - mCurrentBucketStartTimeNs) / mBucketSizeNs;
    mCurrentBucketStartTimeNs = mCurrentBucketStartTimeNs + numBucketsForward * mBucketSizeNs;
    mCurrentBucketNum += numBucketsForward;

    if (numBucketsForward > 1) {
        VLOG("Skipping forward %lld buckets", (long long)numBucketsForward);
    }
    VLOG("metric %s: new bucket start time: %lld", mMetric.name().c_str(),
         (long long)mCurrentBucketStartTimeNs);
}

size_t ValueMetricProducer::byteSizeLocked() const {
    size_t totalSize = 0;
    for (const auto& pair : mPastBuckets) {
        totalSize += pair.second.size() * kBucketSize;
    }
    return totalSize;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
