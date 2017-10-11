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

#include "CountAnomalyTracker.h"
#include "../condition/ConditionTracker.h"
#include "../matchers/matcher_util.h"
#include "MetricProducer.h"
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

using namespace std;

namespace android {
namespace os {
namespace statsd {

class CountMetricProducer : public MetricProducer {
public:
    CountMetricProducer(const CountMetric& countMetric, const bool hasCondition);

    virtual ~CountMetricProducer();

    void onMatchedLogEvent(const LogEventWrapper& event) override;

    void onConditionChanged(const bool conditionMet) override;

    void finish() override;

    void onDumpReport() override;

    // TODO: Implement this later.
    virtual void notifyAppUpgrade(const string& apk, const int uid, const int version) override {};

private:
    const CountMetric mMetric;

    const time_t mStartTime;
    // TODO: Add dimensions.
    // Counter value for the current bucket.
    int mCounter;

    time_t mCurrentBucketStartTime;

    long mBucketSize_sec;

    CountAnomalyTracker mAnomalyTracker;

    bool mCondition;

    void flushCounterIfNeeded(const time_t& newEventTime);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // COUNT_METRIC_PRODUCER_H
