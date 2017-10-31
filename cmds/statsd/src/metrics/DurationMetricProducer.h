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

#ifndef DURATION_METRIC_PRODUCER_H
#define DURATION_METRIC_PRODUCER_H

#include <unordered_map>

#include "../condition/ConditionTracker.h"
#include "../matchers/matcher_util.h"
#include "MetricProducer.h"
#include "duration_helper/DurationTracker.h"
#include "duration_helper/MaxDurationTracker.h"
#include "duration_helper/OringDurationTracker.h"
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "stats_util.h"

using namespace std;

namespace android {
namespace os {
namespace statsd {

class DurationMetricProducer : public MetricProducer {
public:
    DurationMetricProducer(const DurationMetric& durationMetric, const int conditionIndex,
                           const size_t startIndex, const size_t stopIndex,
                           const size_t stopAllIndex, const sp<ConditionWizard>& wizard,
                           const vector<KeyMatcher>& internalDimension);

    virtual ~DurationMetricProducer();

    void onConditionChanged(const bool conditionMet, const uint64_t eventTime) override;

    void finish() override;

    StatsLogReport onDumpReport() override;

    void onSlicedConditionMayChange(const uint64_t eventTime) override;

    size_t byteSize() override;

    // TODO: Implement this later.
    virtual void notifyAppUpgrade(const string& apk, const int uid, const int version) override{};

protected:
    void onMatchedLogEventInternal(const size_t matcherIndex, const HashableDimensionKey& eventKey,
                                   const std::map<std::string, HashableDimensionKey>& conditionKeys,
                                   bool condition, const LogEvent& event) override;

private:
    const DurationMetric mMetric;

    // Index of the SimpleLogEntryMatcher which defines the start.
    const size_t mStartIndex;

    // Index of the SimpleLogEntryMatcher which defines the stop.
    const size_t mStopIndex;

    // Index of the SimpleLogEntryMatcher which defines the stop all for all dimensions.
    const size_t mStopAllIndex;

    // The dimension from the atom predicate. e.g., uid, wakelock name.
    const vector<KeyMatcher> mInternalDimension;

    // Save the past buckets and we can clear when the StatsLogReport is dumped.
    std::unordered_map<HashableDimensionKey, std::vector<DurationBucketInfo>> mPastBuckets;

    // The current bucket.
    std::unordered_map<HashableDimensionKey, std::unique_ptr<DurationTracker>>
            mCurrentSlicedDuration;

    void flushDurationIfNeeded(const uint64_t newEventTime);

    std::unique_ptr<DurationTracker> createDurationTracker(std::vector<DurationBucketInfo>& bucket);

    void flushIfNeeded(uint64_t timestamp);
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // DURATION_METRIC_PRODUCER_H
