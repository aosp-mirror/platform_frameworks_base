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
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "stats_util.h"

using namespace std;

namespace android {
namespace os {
namespace statsd {

enum DurationState {
    kStopped = 0,  // The event is stopped.
    kStarted = 1,  // The event is on going.
    kPaused = 2,   // The event is started, but condition is false, clock is paused. When condition
                   // turns to true, kPaused will become kStarted.
};

// Hold duration information for current on-going bucket.
struct DurationInfo {
    DurationState state;
    // most recent start time.
    int64_t lastStartTime;
    // existing duration in current bucket. Eventually, the duration will be aggregated in
    // the way specified in AggregateType (Sum, Max, or Min).
    int64_t lastDuration;
    // cache the HashableDimensionKeys we need to query the condition for this duration event.
    std::map<string, HashableDimensionKey> conditionKeys;

    DurationInfo() : state(kStopped), lastStartTime(0), lastDuration(0){};
};

class DurationMetricProducer : public MetricProducer {
public:
    DurationMetricProducer(const DurationMetric& durationMetric, const int conditionIndex,
                           const size_t startIndex, const size_t stopIndex,
                           const size_t stopAllIndex, const sp<ConditionWizard>& wizard);

    virtual ~DurationMetricProducer();

    void onMatchedLogEvent(const size_t matcherIndex, const LogEvent& event) override;

    void onConditionChanged(const bool conditionMet) override;

    void finish() override;

    StatsLogReport onDumpReport() override;

    void onSlicedConditionMayChange() override;

    // TODO: Implement this later.
    virtual void notifyAppUpgrade(const string& apk, const int uid, const int version) override{};

private:
    const DurationMetric mMetric;

    // Index of the SimpleLogEntryMatcher which defines the start.
    const size_t mStartIndex;

    // Index of the SimpleLogEntryMatcher which defines the stop.
    const size_t mStopIndex;

    // Index of the SimpleLogEntryMatcher which defines the stop all for all dimensions.
    const size_t mStopAllIndex;

    // Save the past buckets and we can clear when the StatsLogReport is dumped.
    std::unordered_map<HashableDimensionKey, std::vector<DurationBucketInfo>> mPastBuckets;

    // The current bucket.
    std::unordered_map<HashableDimensionKey, DurationInfo> mCurrentSlicedDuration;

    void flushDurationIfNeeded(const uint64_t newEventTime);

    void noteStart(const HashableDimensionKey& key, const bool conditionMet,
                   const uint64_t eventTime);

    void noteStop(const HashableDimensionKey& key, const uint64_t eventTime);

    void noteStopAll(const uint64_t eventTime);

    static int64_t updateDuration(const int64_t lastDuration, const int64_t durationTime,
                                  const DurationMetric_AggregationType type);

    void noteConditionChanged(const HashableDimensionKey& key, const bool conditionMet,
                              const uint64_t eventTime);
};

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // DURATION_METRIC_PRODUCER_H
