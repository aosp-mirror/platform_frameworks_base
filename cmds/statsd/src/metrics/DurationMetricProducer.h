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
    DurationMetricProducer(const ConfigKey& key, const DurationMetric& durationMetric,
                           const int conditionIndex, const size_t startIndex,
                           const size_t stopIndex, const size_t stopAllIndex, const bool nesting,
                           const sp<ConditionWizard>& wizard,
                           const vector<KeyMatcher>& internalDimension, const uint64_t startTimeNs);

    virtual ~DurationMetricProducer();

    void onConditionChanged(const bool conditionMet, const uint64_t eventTime) override;

    void finish() override;
    void flushIfNeeded(const uint64_t newEventTime) override;

    // TODO: Pass a timestamp as a parameter in onDumpReport.
    std::unique_ptr<std::vector<uint8_t>> onDumpReport() override;

    void onSlicedConditionMayChange(const uint64_t eventTime) override;

    size_t byteSize() const override;

    // TODO: Implement this later.
    virtual void notifyAppUpgrade(const string& apk, const int uid, const int version) override{};
    // TODO: Implement this later.
    virtual void notifyAppRemoved(const string& apk, const int uid) override{};

protected:
    void onMatchedLogEventInternal(const size_t matcherIndex, const HashableDimensionKey& eventKey,
                                   const std::map<std::string, HashableDimensionKey>& conditionKeys,
                                   bool condition, const LogEvent& event,
                                   bool scheduledPull) override;

    void startNewProtoOutputStream(long long timestamp) override;

private:
    const DurationMetric mMetric;

    // Index of the SimpleLogEntryMatcher which defines the start.
    const size_t mStartIndex;

    // Index of the SimpleLogEntryMatcher which defines the stop.
    const size_t mStopIndex;

    // Index of the SimpleLogEntryMatcher which defines the stop all for all dimensions.
    const size_t mStopAllIndex;

    // nest counting -- for the same key, stops must match the number of starts to make real stop
    const bool mNested;

    // The dimension from the atom predicate. e.g., uid, wakelock name.
    const vector<KeyMatcher> mInternalDimension;

    // Save the past buckets and we can clear when the StatsLogReport is dumped.
    // TODO: Add a lock to mPastBuckets.
    std::unordered_map<HashableDimensionKey, std::vector<DurationBucket>> mPastBuckets;

    // The current bucket.
    std::unordered_map<HashableDimensionKey, std::unique_ptr<DurationTracker>>
            mCurrentSlicedDuration;

    std::unique_ptr<DurationTracker> createDurationTracker(const HashableDimensionKey& eventKey,
                                                           std::vector<DurationBucket>& bucket);
    bool hitGuardRail(const HashableDimensionKey& newKey);

    static const size_t kBucketSize = sizeof(DurationBucket{});
};

}  // namespace statsd
}  // namespace os
}  // namespace android

