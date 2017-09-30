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
#define LOG_TAG "MetricManager"
#define DEBUG true  // STOPSHIP if true
#define VLOG(...) \
    if (DEBUG) ALOGD(__VA_ARGS__);

#include "MetricsManager.h"
#include <cutils/log.h>
#include <log/logprint.h>
#include "CountMetricProducer.h"
#include "parse_util.h"

using std::make_unique;
using std::set;
using std::string;
using std::unique_ptr;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

MetricsManager::MetricsManager(const StatsdConfig& config) : mConfig(config), mLogMatchers() {
    std::unordered_map<string, LogEntryMatcher> matcherMap;
    std::unordered_map<string, sp<ConditionTracker>> conditionMap;

    for (int i = 0; i < config.log_entry_matcher_size(); i++) {
        const LogEntryMatcher& logMatcher = config.log_entry_matcher(i);
        mMatchers.push_back(logMatcher);

        matcherMap[config.log_entry_matcher(i).name()] = logMatcher;

        mLogMatchers[logMatcher.name()] = vector<unique_ptr<MetricProducer>>();
        // Collect all the tag ids that are interesting
        set<int> tagIds = LogEntryMatcherManager::getTagIdsFromMatcher(logMatcher);

        mTagIds.insert(tagIds.begin(), tagIds.end());
    }

    for (int i = 0; i < config.condition_size(); i++) {
        const Condition& condition = config.condition(i);
        conditionMap[condition.name()] = new ConditionTracker(condition);
    }

    // Build MetricProducers for each metric defined in config.
    // (1) build CountMetricProducer
    for (int i = 0; i < config.count_metric_size(); i++) {
        const CountMetric& metric = config.count_metric(i);
        auto it = mLogMatchers.find(metric.what());
        if (it == mLogMatchers.end()) {
            ALOGW("cannot find the LogEntryMatcher %s in config", metric.what().c_str());
            continue;
        }

        if (metric.has_condition()) {
            auto condition_it = conditionMap.find(metric.condition());
            if (condition_it == conditionMap.end()) {
                ALOGW("cannot find the Condition %s in the config", metric.condition().c_str());
                continue;
            }
            it->second.push_back(make_unique<CountMetricProducer>(metric, condition_it->second));
        } else {
            it->second.push_back(make_unique<CountMetricProducer>(metric));
        }
    }

    // TODO: build other types of metrics too.
}

MetricsManager::~MetricsManager() {
    VLOG("~MetricManager()");
}

void MetricsManager::finish() {
    for (auto const& entryPair : mLogMatchers) {
        for (auto const& metric : entryPair.second) {
            metric->finish();
        }
    }
}

// Consume the stats log if it's interesting to this metric.
void MetricsManager::onLogEvent(const log_msg& logMsg) {
    int tagId = getTagId(logMsg);
    if (mTagIds.find(tagId) == mTagIds.end()) {
        // not interesting...
        return;
    }
    // Since at least one of the metrics is interested in this event, we parse it now.
    LogEventWrapper event = LogEntryMatcherManager::parseLogEvent(logMsg);

    // Evaluate the conditions. Order matters, this should happen
    // before sending the event to metrics
    for (auto& condition : mConditionTracker) {
        condition->evaluateCondition(event);
    }

    // Now find out which LogMatcher matches this event, and let relevant metrics know.
    for (auto matcher : mMatchers) {
        if (LogEntryMatcherManager::matches(matcher, event)) {
            auto it = mLogMatchers.find(matcher.name());
            if (it != mLogMatchers.end()) {
                for (auto const& it2 : it->second) {
                    // Only metrics that matches this event get notified.
                    it2->onMatchedLogEvent(event);
                }
            } else {
                // TODO: we should remove any redundant matchers that the config provides.
                ALOGW("Matcher not used by any metrics.");
            }
        }
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
