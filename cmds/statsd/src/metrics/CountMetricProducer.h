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

#ifndef COUNT_METRIC_PRODUCER_H
#define COUNT_METRIC_PRODUCER_H

#include <unordered_map>

#include "../condition/ConditionTracker.h"
#include "../matchers/matcher_util.h"
#include "CountAnomalyTracker.h"
#include "MetricProducer.h"
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "stats_util.h"

using namespace std;

namespace android {
namespace os {
namespace statsd {

class CountMetricProducer : public MetricProducer {
public:
    // TODO: Pass in the start time from MetricsManager, it should be consistent for all metrics.
    CountMetricProducer(const CountMetric& countMetric, const int conditionIndex,
                        const sp<ConditionWizard>& wizard);

    virtual ~CountMetricProducer();

    void onConditionChanged(const bool conditionMet) override;

    void finish() override;

    StatsLogReport onDumpReport() override;

    void onSlicedConditionMayChange() override;

    size_t byteSize() override;

    // TODO: Implement this later.
    virtual void notifyAppUpgrade(const string& apk, const int uid, const int version) override{};

protected:
    void onMatchedLogEventInternal(const size_t matcherIndex, const HashableDimensionKey& eventKey,
                                   const std::map<std::string, HashableDimensionKey>& conditionKey,
                                   bool condition, const LogEvent& event) override;

private:
    const CountMetric mMetric;

    // Save the past buckets and we can clear when the StatsLogReport is dumped.
    std::unordered_map<HashableDimensionKey, std::vector<CountBucketInfo>> mPastBuckets;

    // The current bucket.
    std::unordered_map<HashableDimensionKey, int> mCurrentSlicedCounter;

    vector<unique_ptr<CountAnomalyTracker>> mAnomalyTrackers;

    void flushCounterIfNeeded(const uint64_t newEventTime);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // COUNT_METRIC_PRODUCER_H
