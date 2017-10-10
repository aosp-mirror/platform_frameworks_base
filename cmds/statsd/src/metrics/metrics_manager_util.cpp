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

#include "../condition/CombinationConditionTracker.h"
#include "../condition/SimpleConditionTracker.h"
#include "../matchers/CombinationLogMatchingTracker.h"
#include "../matchers/SimpleLogMatchingTracker.h"
#include "CountMetricProducer.h"
#include "stats_util.h"

using std::set;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

bool initLogTrackers(const StatsdConfig& config, unordered_map<string, int>& logTrackerMap,
                     vector<sp<LogMatchingTracker>>& allLogEntryMatchers, set<int>& allTagIds) {
    vector<LogEntryMatcher> matcherConfigs;

    for (int i = 0; i < config.log_entry_matcher_size(); i++) {
        const LogEntryMatcher& logMatcher = config.log_entry_matcher(i);

        int index = allLogEntryMatchers.size();
        switch (logMatcher.contents_case()) {
            case LogEntryMatcher::ContentsCase::kSimpleLogEntryMatcher:
                allLogEntryMatchers.push_back(new SimpleLogMatchingTracker(
                        logMatcher.name(), index, logMatcher.simple_log_entry_matcher()));
                break;
            case LogEntryMatcher::ContentsCase::kCombination:
                allLogEntryMatchers.push_back(
                        new CombinationLogMatchingTracker(logMatcher.name(), index));
                break;
            default:
                ALOGE("Matcher %s malformed", logMatcher.name().c_str());
                return false;
                // continue;
        }
        if (logTrackerMap.find(logMatcher.name()) != logTrackerMap.end()) {
            ALOGE("Duplicate LogEntryMatcher found!");
            return false;
        }
        logTrackerMap[logMatcher.name()] = index;
        matcherConfigs.push_back(logMatcher);
    }

    vector<bool> stackTracker2(allLogEntryMatchers.size(), false);
    for (auto& matcher : allLogEntryMatchers) {
        if (!matcher->init(matcherConfigs, allLogEntryMatchers, logTrackerMap, stackTracker2)) {
            return false;
        }
        // Collect all the tag ids that are interesting. TagIds exist in leaf nodes only.
        const set<int>& tagIds = matcher->getTagIds();
        allTagIds.insert(tagIds.begin(), tagIds.end());
    }
    return true;
}

bool initConditions(const StatsdConfig& config, const unordered_map<string, int>& logTrackerMap,
                    unordered_map<string, int>& conditionTrackerMap,
                    vector<sp<ConditionTracker>>& allConditionTrackers,
                    unordered_map<int, std::vector<int>>& trackerToConditionMap) {
    vector<Condition> conditionConfigs;

    for (int i = 0; i < config.condition_size(); i++) {
        const Condition& condition = config.condition(i);
        int index = allConditionTrackers.size();
        switch (condition.contents_case()) {
            case Condition::ContentsCase::kSimpleCondition: {
                allConditionTrackers.push_back(new SimpleConditionTracker(
                        condition.name(), index, condition.simple_condition(), logTrackerMap));
                break;
            }
            case Condition::ContentsCase::kCombination: {
                allConditionTrackers.push_back(
                        new CombinationConditionTracker(condition.name(), index));
                break;
            }
            default:
                ALOGE("Condition %s malformed", condition.name().c_str());
                return false;
        }
        if (conditionTrackerMap.find(condition.name()) != conditionTrackerMap.end()) {
            ALOGE("Duplicate Condition found!");
            return false;
        }
        conditionTrackerMap[condition.name()] = index;
        conditionConfigs.push_back(condition);
    }

    vector<bool> stackTracker(allConditionTrackers.size(), false);
    for (size_t i = 0; i < allConditionTrackers.size(); i++) {
        auto& conditionTracker = allConditionTrackers[i];
        if (!conditionTracker->init(conditionConfigs, allConditionTrackers, conditionTrackerMap,
                                    stackTracker)) {
            return false;
        }
        for (const int trackerIndex : conditionTracker->getLogTrackerIndex()) {
            auto& conditionList = trackerToConditionMap[trackerIndex];
            conditionList.push_back(i);
        }
    }
    return true;
}

bool initMetrics(const StatsdConfig& config, const unordered_map<string, int>& logTrackerMap,
                 const unordered_map<string, int>& conditionTrackerMap,
                 vector<sp<MetricProducer>>& allMetricProducers,
                 unordered_map<int, std::vector<int>>& conditionToMetricMap,
                 unordered_map<int, std::vector<int>>& trackerToMetricMap) {
    // Build MetricProducers for each metric defined in config.
    // (1) build CountMetricProducer
    for (int i = 0; i < config.count_metric_size(); i++) {
        const CountMetric& metric = config.count_metric(i);
        if (!metric.has_what()) {
            ALOGW("cannot find what in CountMetric %lld", metric.metric_id());
            return false;
        }

        auto logTrackerIt = logTrackerMap.find(metric.what());
        if (logTrackerIt == logTrackerMap.end()) {
            ALOGW("cannot find the LogEntryMatcher %s in config", metric.what().c_str());
            return false;
        }

        sp<MetricProducer> countProducer;
        int metricIndex = allMetricProducers.size();
        if (metric.has_condition()) {
            auto condition_it = conditionTrackerMap.find(metric.condition());
            if (condition_it == conditionTrackerMap.end()) {
                ALOGW("cannot find the Condition %s in the config", metric.condition().c_str());
                return false;
            }
            countProducer = new CountMetricProducer(metric, true /*has condition*/);
            // will create new vector if not exist before.
            auto& metricList = conditionToMetricMap[condition_it->second];
            metricList.push_back(metricIndex);
        } else {
            countProducer = new CountMetricProducer(metric, false /*no condition*/);
        }

        int logTrackerIndex = logTrackerIt->second;
        auto& metric_list = trackerToMetricMap[logTrackerIndex];
        metric_list.push_back(metricIndex);
        allMetricProducers.push_back(countProducer);
    }

    // TODO: build other types of metrics too.

    return true;
}

bool initStatsdConfig(const StatsdConfig& config, set<int>& allTagIds,
                      vector<sp<LogMatchingTracker>>& allLogEntryMatchers,
                      vector<sp<ConditionTracker>>& allConditionTrackers,
                      vector<sp<MetricProducer>>& allMetricProducers,
                      unordered_map<int, std::vector<int>>& conditionToMetricMap,
                      unordered_map<int, std::vector<int>>& trackerToMetricMap,
                      unordered_map<int, std::vector<int>>& trackerToConditionMap) {
    unordered_map<string, int> logTrackerMap;
    unordered_map<string, int> conditionTrackerMap;

    if (!initLogTrackers(config, logTrackerMap, allLogEntryMatchers, allTagIds)) {
        ALOGE("initLogMatchingTrackers failed");
        return false;
    }

    if (!initConditions(config, logTrackerMap, conditionTrackerMap, allConditionTrackers,
                        trackerToConditionMap)) {
        ALOGE("initConditionTrackers failed");
        return false;
    }

    if (!initMetrics(config, logTrackerMap, conditionTrackerMap, allMetricProducers,
                     conditionToMetricMap, trackerToMetricMap)) {
        ALOGE("initMetricProducers failed");
        return false;
    }
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
