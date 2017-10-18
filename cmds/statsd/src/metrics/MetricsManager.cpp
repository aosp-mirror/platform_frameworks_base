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
#define VLOG(...) \
    if (DEBUG) ALOGD(__VA_ARGS__);

#include "MetricsManager.h"
#include <log/logprint.h>
#include "../condition/CombinationConditionTracker.h"
#include "../condition/SimpleConditionTracker.h"
#include "../matchers/CombinationLogMatchingTracker.h"
#include "../matchers/SimpleLogMatchingTracker.h"
#include "CountMetricProducer.h"
#include "metrics_manager_util.h"
#include "stats_util.h"

using std::make_unique;
using std::set;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

MetricsManager::MetricsManager(const StatsdConfig& config) {
    mConfigValid = initStatsdConfig(config, mTagIds, mAllLogEntryMatchers, mAllConditionTrackers,
                                    mAllMetricProducers, mConditionToMetricMap, mTrackerToMetricMap,
                                    mTrackerToConditionMap);
}

MetricsManager::~MetricsManager() {
    VLOG("~MetricManager()");
}

bool MetricsManager::isConfigValid() const {
    return mConfigValid;
}

void MetricsManager::finish() {
    for (auto& metricProducer : mAllMetricProducers) {
        metricProducer->finish();
    }
}

// Consume the stats log if it's interesting to this metric.
void MetricsManager::onLogEvent(const LogEvent& event) {
    if (!mConfigValid) {
        return;
    }

    int tagId = event.GetTagId();
    if (mTagIds.find(tagId) == mTagIds.end()) {
        // not interesting...
        return;
    }

    // Since at least one of the metrics is interested in this event, we parse it now.
    vector<MatchingState> matcherCache(mAllLogEntryMatchers.size(), MatchingState::kNotComputed);

    for (auto& matcher : mAllLogEntryMatchers) {
        matcher->onLogEvent(event, mAllLogEntryMatchers, matcherCache);
    }

    // A bitmap to see which ConditionTracker needs to be re-evaluated.
    vector<bool> conditionToBeEvaluated(mAllConditionTrackers.size(), false);

    for (const auto& pair : mTrackerToConditionMap) {
        if (matcherCache[pair.first] == MatchingState::kMatched) {
            const auto& conditionList = pair.second;
            for (const int conditionIndex : conditionList) {
                conditionToBeEvaluated[conditionIndex] = true;
            }
        }
    }

    vector<ConditionState> conditionCache(mAllConditionTrackers.size(),
                                          ConditionState::kNotEvaluated);
    // A bitmap to track if a condition has changed value.
    vector<bool> changedCache(mAllConditionTrackers.size(), false);
    for (size_t i = 0; i < mAllConditionTrackers.size(); i++) {
        if (conditionToBeEvaluated[i] == false) {
            continue;
        }

        sp<ConditionTracker>& condition = mAllConditionTrackers[i];
        condition->evaluateCondition(event, matcherCache, mAllConditionTrackers, conditionCache,
                                     changedCache);
        if (changedCache[i]) {
            auto pair = mConditionToMetricMap.find(i);
            if (pair != mConditionToMetricMap.end()) {
                auto& metricList = pair->second;
                for (auto metricIndex : metricList) {
                    mAllMetricProducers[metricIndex]->onConditionChanged(conditionCache[i]);
                }
            }
        }
    }

    // For matched LogEntryMatchers, tell relevant metrics that a matched event has come.
    for (size_t i = 0; i < mAllLogEntryMatchers.size(); i++) {
        if (matcherCache[i] == MatchingState::kMatched) {
            auto pair = mTrackerToMetricMap.find(i);
            if (pair != mTrackerToMetricMap.end()) {
                auto& metricList = pair->second;
                for (const int metricIndex : metricList) {
                    mAllMetricProducers[metricIndex]->onMatchedLogEvent(event);
                }
            }
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
