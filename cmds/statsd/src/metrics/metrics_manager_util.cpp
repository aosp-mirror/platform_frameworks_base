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
#include "DurationMetricProducer.h"
#include "EventMetricProducer.h"
#include "stats_util.h"

using std::set;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

bool handleMetricWithLogTrackers(const string what, const int metricIndex,
                                 const unordered_map<string, int>& logTrackerMap,
                                 unordered_map<int, std::vector<int>>& trackerToMetricMap,
                                 int& logTrackerIndex) {
    auto logTrackerIt = logTrackerMap.find(what);
    if (logTrackerIt == logTrackerMap.end()) {
        ALOGW("cannot find the LogEntryMatcher %s in config", what.c_str());
        return false;
    }
    logTrackerIndex = logTrackerIt->second;
    auto& metric_list = trackerToMetricMap[logTrackerIndex];
    metric_list.push_back(metricIndex);
    return true;
}

bool handleMetricWithConditions(
        const string condition, const int metricIndex,
        const unordered_map<string, int>& conditionTrackerMap,
        const ::google::protobuf::RepeatedPtrField<::android::os::statsd::EventConditionLink>&
                links,
        vector<sp<ConditionTracker>>& allConditionTrackers, int& conditionIndex,
        unordered_map<int, std::vector<int>>& conditionToMetricMap) {
    auto condition_it = conditionTrackerMap.find(condition);
    if (condition_it == conditionTrackerMap.end()) {
        ALOGW("cannot find the Condition %s in the config", condition.c_str());
        return false;
    }

    for (const auto& link : links) {
        auto it = conditionTrackerMap.find(link.condition());
        if (it == conditionTrackerMap.end()) {
            ALOGW("cannot find the Condition %s in the config", link.condition().c_str());
            return false;
        }
        allConditionTrackers[condition_it->second]->setSliced(true);
        allConditionTrackers[it->second]->setSliced(true);
        allConditionTrackers[it->second]->addDimensions(
                vector<KeyMatcher>(link.key_in_condition().begin(), link.key_in_condition().end()));
    }
    conditionIndex = condition_it->second;

    // will create new vector if not exist before.
    auto& metricList = conditionToMetricMap[condition_it->second];
    metricList.push_back(metricIndex);
    return true;
}

bool initLogTrackers(const StatsdConfig& config, unordered_map<string, int>& logTrackerMap,
                     vector<sp<LogMatchingTracker>>& allLogEntryMatchers, set<int>& allTagIds) {
    vector<LogEntryMatcher> matcherConfigs;
    const int logEntryMatcherCount = config.log_entry_matcher_size();
    matcherConfigs.reserve(logEntryMatcherCount);
    allLogEntryMatchers.reserve(logEntryMatcherCount);

    for (int i = 0; i < logEntryMatcherCount; i++) {
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
    const int conditionTrackerCount = config.condition_size();
    conditionConfigs.reserve(conditionTrackerCount);
    allConditionTrackers.reserve(conditionTrackerCount);

    for (int i = 0; i < conditionTrackerCount; i++) {
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
                 vector<sp<ConditionTracker>>& allConditionTrackers,
                 vector<sp<MetricProducer>>& allMetricProducers,
                 unordered_map<int, std::vector<int>>& conditionToMetricMap,
                 unordered_map<int, std::vector<int>>& trackerToMetricMap) {
    sp<ConditionWizard> wizard = new ConditionWizard(allConditionTrackers);
    const int allMetricsCount =
            config.count_metric_size() + config.duration_metric_size() + config.event_metric_size();
    allMetricProducers.reserve(allMetricsCount);

    // Build MetricProducers for each metric defined in config.
    // (1) build CountMetricProducer
    for (int i = 0; i < config.count_metric_size(); i++) {
        const CountMetric& metric = config.count_metric(i);
        if (!metric.has_what()) {
            ALOGW("cannot find what in CountMetric %lld", metric.metric_id());
            return false;
        }

        int metricIndex = allMetricProducers.size();
        int trackerIndex;
        if (!handleMetricWithLogTrackers(metric.what(), metricIndex, logTrackerMap,
                                         trackerToMetricMap, trackerIndex)) {
            return false;
        }

        int conditionIndex = -1;
        if (metric.has_condition()) {
            handleMetricWithConditions(metric.condition(), metricIndex, conditionTrackerMap,
                                       metric.links(), allConditionTrackers, conditionIndex,
                                       conditionToMetricMap);
        }

        sp<MetricProducer> countProducer = new CountMetricProducer(metric, conditionIndex, wizard);
        allMetricProducers.push_back(countProducer);
    }

    for (int i = 0; i < config.duration_metric_size(); i++) {
        int metricIndex = allMetricProducers.size();
        const DurationMetric& metric = config.duration_metric(i);
        int trackerIndices[3] = {-1, -1, -1};
        if (!metric.has_start() ||
            !handleMetricWithLogTrackers(metric.start(), metricIndex, logTrackerMap,
                                         trackerToMetricMap, trackerIndices[0])) {
            ALOGE("Duration metrics must specify a valid the start event matcher");
            return false;
        }

        if (metric.has_stop() &&
            !handleMetricWithLogTrackers(metric.stop(), metricIndex, logTrackerMap,
                                         trackerToMetricMap, trackerIndices[1])) {
            return false;
        }

        if (metric.has_stop_all() &&
            !handleMetricWithLogTrackers(metric.stop_all(), metricIndex, logTrackerMap,
                                         trackerToMetricMap, trackerIndices[2])) {
            return false;
        }

        int conditionIndex = -1;

        if (metric.has_predicate()) {
            handleMetricWithConditions(metric.predicate(), metricIndex, conditionTrackerMap,
                                       metric.links(), allConditionTrackers, conditionIndex,
                                       conditionToMetricMap);
        }

        sp<MetricProducer> durationMetric =
                new DurationMetricProducer(metric, conditionIndex, trackerIndices[0],
                                           trackerIndices[1], trackerIndices[2], wizard);

        allMetricProducers.push_back(durationMetric);
    }

    for (int i = 0; i < config.event_metric_size(); i++) {
        int metricIndex = allMetricProducers.size();
        const EventMetric& metric = config.event_metric(i);
        if (!metric.has_metric_id() || !metric.has_what()) {
            ALOGW("cannot find the metric id or what in config");
            return false;
        }
        int trackerIndex;
        if (!handleMetricWithLogTrackers(metric.what(), metricIndex, logTrackerMap,
                                         trackerToMetricMap, trackerIndex)) {
            return false;
        }

        int conditionIndex = -1;
        if (metric.has_condition()) {
            handleMetricWithConditions(metric.condition(), metricIndex, conditionTrackerMap,
                                       metric.links(), allConditionTrackers, conditionIndex,
                                       conditionToMetricMap);
        }

        sp<MetricProducer> eventMetric = new EventMetricProducer(metric, conditionIndex, wizard);

        allMetricProducers.push_back(eventMetric);
    }

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

    if (!initMetrics(config, logTrackerMap, conditionTrackerMap, allConditionTrackers,
                     allMetricProducers, conditionToMetricMap, trackerToMetricMap)) {
        ALOGE("initMetricProducers failed");
        return false;
    }
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
