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
#include "MetricProducer.h"

namespace android {
namespace os {
namespace statsd {

using std::map;

void MetricProducer::onMatchedLogEventLocked(const size_t matcherIndex, const LogEvent& event) {
    int64_t eventTimeNs = event.GetElapsedTimestampNs();
    // this is old event, maybe statsd restarted?
    if (eventTimeNs < mTimeBaseNs) {
        return;
    }

    bool condition;
    ConditionKey conditionKey;
    std::unordered_set<HashableDimensionKey> dimensionKeysInCondition;
    if (mConditionSliced) {
        for (const auto& link : mMetric2ConditionLinks) {
            getDimensionForCondition(event.getValues(), link, &conditionKey[link.conditionId]);
        }
        auto conditionState =
            mWizard->query(mConditionTrackerIndex, conditionKey, mDimensionsInCondition,
                           !mSameConditionDimensionsInTracker,
                           !mHasLinksToAllConditionDimensionsInTracker,
                           &dimensionKeysInCondition);
        condition = (conditionState == ConditionState::kTrue);
    } else {
        condition = mCondition;
    }

    if (mDimensionsInCondition.empty() && condition) {
        dimensionKeysInCondition.insert(DEFAULT_DIMENSION_KEY);
    }

    HashableDimensionKey dimensionInWhat;
    filterValues(mDimensionsInWhat, event.getValues(), &dimensionInWhat);
    MetricDimensionKey metricKey(dimensionInWhat, DEFAULT_DIMENSION_KEY);
    for (const auto& conditionDimensionKey : dimensionKeysInCondition) {
        metricKey.setDimensionKeyInCondition(conditionDimensionKey);
        onMatchedLogEventInternalLocked(
                matcherIndex, metricKey, conditionKey, condition, event);
    }
    if (dimensionKeysInCondition.empty()) {
        onMatchedLogEventInternalLocked(
                matcherIndex, metricKey, conditionKey, condition, event);
    }

 }

}  // namespace statsd
}  // namespace os
}  // namespace android
