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
#include "MetricProducer.h"

namespace android {
namespace os {
namespace statsd {

using std::map;

void MetricProducer::onMatchedLogEventLocked(const size_t matcherIndex, const LogEvent& event) {
    uint64_t eventTimeNs = event.GetTimestampNs();
    // this is old event, maybe statsd restarted?
    if (eventTimeNs < mStartTimeNs) {
        return;
    }

    bool condition;
    ConditionKey conditionKey;
    if (mConditionSliced) {
        for (const auto& link : mConditionLinks) {
            getDimensionKeysForCondition(event, link, &conditionKey[link.condition()]);
        }
        if (mWizard->query(mConditionTrackerIndex, conditionKey) != ConditionState::kTrue) {
            condition = false;
        } else {
            condition = true;
        }
    } else {
        condition = mCondition;
    }

    if (mDimensions.has_field() && mDimensions.child_size() > 0) {
        vector<DimensionsValue> dimensionValues;
        getDimensionKeys(event, mDimensions, &dimensionValues);
        for (const DimensionsValue& dimensionValue : dimensionValues) {
            onMatchedLogEventInternalLocked(
                matcherIndex, HashableDimensionKey(dimensionValue), conditionKey, condition, event);
        }
    } else {
        onMatchedLogEventInternalLocked(
            matcherIndex, DEFAULT_DIMENSION_KEY, conditionKey, condition, event);
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
