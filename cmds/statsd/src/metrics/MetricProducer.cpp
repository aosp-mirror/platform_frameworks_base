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

void MetricProducer::onMatchedLogEvent(const size_t matcherIndex, const LogEvent& event) {
    uint64_t eventTimeNs = event.GetTimestampNs();
    // this is old event, maybe statsd restarted?
    if (eventTimeNs < mStartTimeNs) {
        return;
    }

    HashableDimensionKey eventKey;

    if (mDimension.size() > 0) {
        vector<KeyValuePair> key = getDimensionKey(event, mDimension);
        eventKey = getHashableKey(key);
        // Add the HashableDimensionKey->vector<KeyValuePair> to the map, because StatsLogReport
        // expects vector<KeyValuePair>.
        if (mDimensionKeyMap.find(eventKey) == mDimensionKeyMap.end()) {
            mDimensionKeyMap[eventKey] = key;
        }
    } else {
        eventKey = DEFAULT_DIMENSION_KEY;
    }

    bool condition;

    map<string, HashableDimensionKey> conditionKeys;
    if (mConditionSliced) {
        for (const auto& link : mConditionLinks) {
            HashableDimensionKey conditionKey = getDimensionKeyForCondition(event, link);
            conditionKeys[link.condition()] = conditionKey;
        }
        if (mWizard->query(mConditionTrackerIndex, conditionKeys) != ConditionState::kTrue) {
            condition = false;
        } else {
            condition = true;
        }
    } else {
        condition = mCondition;
    }

    onMatchedLogEventInternal(matcherIndex, eventKey, conditionKeys, condition, event);
}

}  // namespace statsd
}  // namespace os
}  // namespace android