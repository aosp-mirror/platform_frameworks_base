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

#include <mutex>
#include <thread>
#include <unordered_map>
#include "../matchers/LogEntryMatcherManager.h"
#include "ConditionTracker.h"
#include "DropboxWriter.h"
#include "MetricProducer.h"
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

namespace android {
namespace os {
namespace statsd {

class CountMetricProducer : public MetricProducer {
public:
    CountMetricProducer(const CountMetric& countMetric, const sp<ConditionTracker> condition);

    CountMetricProducer(const CountMetric& countMetric);

    virtual ~CountMetricProducer();

    void onMatchedLogEvent(const LogEventWrapper& event) override;

    void finish() override;

    void onDumpReport() override;

private:
    const CountMetric mMetric;

    const sp<ConditionTracker> mConditionTracker;

    const time_t mStartTime;
    // TODO: Add dimensions.
    int mCounter;

    time_t mCurrentBucketStartTime;

    long mBucketSize_sec;

    void flushCounterIfNeeded(const time_t& newEventTime);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // COUNT_METRIC_PRODUCER_H
