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
#define DEBUG false  // STOPSHIP if true
#include "Log.h"
#include "MetricsManager.h"
#include "statslog.h"

#include "CountMetricProducer.h"
#include "condition/CombinationConditionTracker.h"
#include "condition/SimpleConditionTracker.h"
#include "guardrail/StatsdStats.h"
#include "matchers/CombinationLogMatchingTracker.h"
#include "matchers/SimpleLogMatchingTracker.h"
#include "metrics_manager_util.h"
#include "stats_util.h"
#include "stats_log_util.h"

#include <log/logprint.h>
#include <private/android_filesystem_config.h>
#include <utils/SystemClock.h>

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_INT32;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::FIELD_TYPE_STRING;
using android::util::ProtoOutputStream;

using std::make_unique;
using std::set;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

const int FIELD_ID_METRICS = 1;
const int FIELD_ID_ANNOTATIONS = 7;
const int FIELD_ID_ANNOTATIONS_INT64 = 1;
const int FIELD_ID_ANNOTATIONS_INT32 = 2;

// for ActiveConfig
const int FIELD_ID_ACTIVE_CONFIG_ID = 1;
const int FIELD_ID_ACTIVE_CONFIG_UID = 2;
const int FIELD_ID_ACTIVE_CONFIG_METRIC = 3;

MetricsManager::MetricsManager(const ConfigKey& key, const StatsdConfig& config,
                               const int64_t timeBaseNs, const int64_t currentTimeNs,
                               const sp<UidMap>& uidMap,
                               const sp<StatsPullerManager>& pullerManager,
                               const sp<AlarmMonitor>& anomalyAlarmMonitor,
                               const sp<AlarmMonitor>& periodicAlarmMonitor)
    : mConfigKey(key),
      mUidMap(uidMap),
      mTtlNs(config.has_ttl_in_seconds() ? config.ttl_in_seconds() * NS_PER_SEC : -1),
      mTtlEndNs(-1),
      mLastReportTimeNs(currentTimeNs),
      mLastReportWallClockNs(getWallClockNs()),
      mShouldPersistHistory(config.persist_locally()) {
    // Init the ttl end timestamp.
    refreshTtl(timeBaseNs);

    mConfigValid = initStatsdConfig(
            key, config, *uidMap, pullerManager, anomalyAlarmMonitor, periodicAlarmMonitor,
            timeBaseNs, currentTimeNs, mTagIds, mAllAtomMatchers, mAllConditionTrackers,
            mAllMetricProducers, mAllAnomalyTrackers, mAllPeriodicAlarmTrackers,
            mConditionToMetricMap, mTrackerToMetricMap, mTrackerToConditionMap,
            mActivationAtomTrackerToMetricMap, mDeactivationAtomTrackerToMetricMap,
            mMetricIndexesWithActivation, mNoReportMetricIds);

    mHashStringsInReport = config.hash_strings_in_metric_report();
    mVersionStringsInReport = config.version_strings_in_metric_report();
    mInstallerInReport = config.installer_in_metric_report();

    if (config.allowed_log_source_size() == 0) {
        mConfigValid = false;
        ALOGE("Log source whitelist is empty! This config won't get any data. Suggest adding at "
                      "least AID_SYSTEM and AID_STATSD to the allowed_log_source field.");
    } else {
        for (const auto& source : config.allowed_log_source()) {
            auto it = UidMap::sAidToUidMapping.find(source);
            if (it != UidMap::sAidToUidMapping.end()) {
                mAllowedUid.push_back(it->second);
            } else {
                mAllowedPkg.push_back(source);
            }
        }

        if (mAllowedUid.size() + mAllowedPkg.size() > StatsdStats::kMaxLogSourceCount) {
            ALOGE("Too many log sources. This is likely to be an error in the config.");
            mConfigValid = false;
        } else {
            initLogSourceWhiteList();
        }
    }

    // Store the sub-configs used.
    for (const auto& annotation : config.annotation()) {
        mAnnotations.emplace_back(annotation.field_int64(), annotation.field_int32());
    }

    // Guardrail. Reject the config if it's too big.
    if (mAllMetricProducers.size() > StatsdStats::kMaxMetricCountPerConfig ||
        mAllConditionTrackers.size() > StatsdStats::kMaxConditionCountPerConfig ||
        mAllAtomMatchers.size() > StatsdStats::kMaxMatcherCountPerConfig) {
        ALOGE("This config is too big! Reject!");
        mConfigValid = false;
    }
    if (mAllAnomalyTrackers.size() > StatsdStats::kMaxAlertCountPerConfig) {
        ALOGE("This config has too many alerts! Reject!");
        mConfigValid = false;
    }

    mIsAlwaysActive = (mMetricIndexesWithActivation.size() != mAllMetricProducers.size()) ||
            (mAllMetricProducers.size() == 0);
    bool isActive = mIsAlwaysActive;
    for (int metric : mMetricIndexesWithActivation) {
        isActive |= mAllMetricProducers[metric]->isActive();
    }
    mIsActive = isActive;
    VLOG("mIsActive is initialized to %d", mIsActive)

    // no matter whether this config is valid, log it in the stats.
    StatsdStats::getInstance().noteConfigReceived(
            key, mAllMetricProducers.size(), mAllConditionTrackers.size(), mAllAtomMatchers.size(),
            mAllAnomalyTrackers.size(), mAnnotations, mConfigValid);
    // Check active
    for (const auto& metric : mAllMetricProducers) {
        if (metric->isActive()) {
            mIsActive = true;
            break;
        }
    }
}

MetricsManager::~MetricsManager() {
    VLOG("~MetricsManager()");
}

void MetricsManager::initLogSourceWhiteList() {
    std::lock_guard<std::mutex> lock(mAllowedLogSourcesMutex);
    mAllowedLogSources.clear();
    mAllowedLogSources.insert(mAllowedUid.begin(), mAllowedUid.end());

    for (const auto& pkg : mAllowedPkg) {
        auto uids = mUidMap->getAppUid(pkg);
        mAllowedLogSources.insert(uids.begin(), uids.end());
    }
    if (DEBUG) {
        for (const auto& uid : mAllowedLogSources) {
            VLOG("Allowed uid %d", uid);
        }
    }
}

bool MetricsManager::isConfigValid() const {
    return mConfigValid;
}

void MetricsManager::notifyAppUpgrade(const int64_t& eventTimeNs, const string& apk, const int uid,
                                      const int64_t version) {
    // check if we care this package
    if (std::find(mAllowedPkg.begin(), mAllowedPkg.end(), apk) == mAllowedPkg.end()) {
        return;
    }
    // We will re-initialize the whole list because we don't want to keep the multi mapping of
    // UID<->pkg inside MetricsManager to reduce the memory usage.
    initLogSourceWhiteList();
}

void MetricsManager::notifyAppRemoved(const int64_t& eventTimeNs, const string& apk,
                                      const int uid) {
    // check if we care this package
    if (std::find(mAllowedPkg.begin(), mAllowedPkg.end(), apk) == mAllowedPkg.end()) {
        return;
    }
    // We will re-initialize the whole list because we don't want to keep the multi mapping of
    // UID<->pkg inside MetricsManager to reduce the memory usage.
    initLogSourceWhiteList();
}

void MetricsManager::onUidMapReceived(const int64_t& eventTimeNs) {
    if (mAllowedPkg.size() == 0) {
        return;
    }
    initLogSourceWhiteList();
}

void MetricsManager::dumpStates(FILE* out, bool verbose) {
    fprintf(out, "ConfigKey %s, allowed source:", mConfigKey.ToString().c_str());
    {
        std::lock_guard<std::mutex> lock(mAllowedLogSourcesMutex);
        for (const auto& source : mAllowedLogSources) {
            fprintf(out, "%d ", source);
        }
    }
    fprintf(out, "\n");
    for (const auto& producer : mAllMetricProducers) {
        producer->dumpStates(out, verbose);
    }
}

void MetricsManager::dropData(const int64_t dropTimeNs) {
    for (const auto& producer : mAllMetricProducers) {
        producer->dropData(dropTimeNs);
    }
}

void MetricsManager::onDumpReport(const int64_t dumpTimeStampNs,
                                  const bool include_current_partial_bucket,
                                  const bool erase_data,
                                  const DumpLatency dumpLatency,
                                  std::set<string> *str_set,
                                  ProtoOutputStream* protoOutput) {
    VLOG("=========================Metric Reports Start==========================");
    // one StatsLogReport per MetricProduer
    for (const auto& producer : mAllMetricProducers) {
        if (mNoReportMetricIds.find(producer->getMetricId()) == mNoReportMetricIds.end()) {
            uint64_t token = protoOutput->start(
                    FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_METRICS);
            if (mHashStringsInReport) {
                producer->onDumpReport(dumpTimeStampNs, include_current_partial_bucket, erase_data,
                                       dumpLatency, str_set, protoOutput);
            } else {
                producer->onDumpReport(dumpTimeStampNs, include_current_partial_bucket, erase_data,
                                       dumpLatency, nullptr, protoOutput);
            }
            protoOutput->end(token);
        } else {
            producer->clearPastBuckets(dumpTimeStampNs);
        }
    }
    for (const auto& annotation : mAnnotations) {
        uint64_t token = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED |
                                            FIELD_ID_ANNOTATIONS);
        protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_ANNOTATIONS_INT64,
                           (long long)annotation.first);
        protoOutput->write(FIELD_TYPE_INT32 | FIELD_ID_ANNOTATIONS_INT32, annotation.second);
        protoOutput->end(token);
    }

    mLastReportTimeNs = dumpTimeStampNs;
    mLastReportWallClockNs = getWallClockNs();
    VLOG("=========================Metric Reports End==========================");
}


bool MetricsManager::checkLogCredentials(const LogEvent& event) {
    if (android::util::AtomsInfo::kWhitelistedAtoms.find(event.GetTagId()) !=
      android::util::AtomsInfo::kWhitelistedAtoms.end())
    {
        return true;
    }
    std::lock_guard<std::mutex> lock(mAllowedLogSourcesMutex);
    if (mAllowedLogSources.find(event.GetUid()) == mAllowedLogSources.end()) {
        VLOG("log source %d not on the whitelist", event.GetUid());
        return false;
    }
    return true;
}

bool MetricsManager::eventSanityCheck(const LogEvent& event) {
    if (event.GetTagId() == android::util::APP_BREADCRUMB_REPORTED) {
        // Check that app breadcrumb reported fields are valid.
        status_t err = NO_ERROR;

        // Uid is 3rd from last field and must match the caller's uid,
        // unless that caller is statsd itself (statsd is allowed to spoof uids).
        long appHookUid = event.GetLong(event.size()-2, &err);
        if (err != NO_ERROR ) {
            VLOG("APP_BREADCRUMB_REPORTED had error when parsing the uid");
            return false;
        }
        int32_t loggerUid = event.GetUid();
        if (loggerUid != appHookUid && loggerUid != AID_STATSD) {
            VLOG("APP_BREADCRUMB_REPORTED has invalid uid: claimed %ld but caller is %d",
                 appHookUid, loggerUid);
            return false;
        }

        // The state must be from 0,3. This part of code must be manually updated.
        long appHookState = event.GetLong(event.size(), &err);
        if (err != NO_ERROR ) {
            VLOG("APP_BREADCRUMB_REPORTED had error when parsing the state field");
            return false;
        } else if (appHookState < 0 || appHookState > 3) {
            VLOG("APP_BREADCRUMB_REPORTED does not have valid state %ld", appHookState);
            return false;
        }
    } else if (event.GetTagId() == android::util::DAVEY_OCCURRED) {
        // Daveys can be logged from any app since they are logged in libs/hwui/JankTracker.cpp.
        // Check that the davey duration is reasonable. Max length check is for privacy.
        status_t err = NO_ERROR;

        // Uid is the first field provided.
        long jankUid = event.GetLong(1, &err);
        if (err != NO_ERROR ) {
            VLOG("Davey occurred had error when parsing the uid");
            return false;
        }
        int32_t loggerUid = event.GetUid();
        if (loggerUid != jankUid && loggerUid != AID_STATSD) {
            VLOG("DAVEY_OCCURRED has invalid uid: claimed %ld but caller is %d", jankUid,
                 loggerUid);
            return false;
        }

        long duration = event.GetLong(event.size(), &err);
        if (err != NO_ERROR ) {
            VLOG("Davey occurred had error when parsing the duration");
            return false;
        } else if (duration > 100000) {
            VLOG("Davey duration is unreasonably long: %ld", duration);
            return false;
        }
    }

    return true;
}

// Consume the stats log if it's interesting to this metric.
void MetricsManager::onLogEvent(const LogEvent& event) {
    if (!mConfigValid) {
        return;
    }

    if (!checkLogCredentials(event)) {
        return;
    }

    if (!eventSanityCheck(event)) {
        return;
    }

    int tagId = event.GetTagId();
    int64_t eventTimeNs = event.GetElapsedTimestampNs();

    bool isActive = mIsAlwaysActive;

    // Set of metrics that are still active after flushing.
    unordered_set<int> activeMetricsIndices;

    // Update state of all metrics w/ activation conditions as of eventTimeNs.
    for (int metricIndex : mMetricIndexesWithActivation) {
        const sp<MetricProducer>& metric = mAllMetricProducers[metricIndex];
        metric->flushIfExpire(eventTimeNs);
        if (metric->isActive()) {
            // If this metric w/ activation condition is still active after
            // flushing, remember it.
            activeMetricsIndices.insert(metricIndex);
        }
    }

    mIsActive = isActive || !activeMetricsIndices.empty();

    if (mTagIds.find(tagId) == mTagIds.end()) {
        // Not interesting...
        return;
    }

    vector<MatchingState> matcherCache(mAllAtomMatchers.size(), MatchingState::kNotComputed);

    // Evaluate all atom matchers.
    for (auto& matcher : mAllAtomMatchers) {
        matcher->onLogEvent(event, mAllAtomMatchers, matcherCache);
    }

    // Set of metrics that received an activation cancellation.
    unordered_set<int> metricIndicesWithCanceledActivations;

    // Determine which metric activations received a cancellation and cancel them.
    for (const auto& it : mDeactivationAtomTrackerToMetricMap) {
        if (matcherCache[it.first] == MatchingState::kMatched) {
            for (int metricIndex : it.second) {
                mAllMetricProducers[metricIndex]->cancelEventActivation(it.first);
                metricIndicesWithCanceledActivations.insert(metricIndex);
            }
        }
    }

    // Determine whether any metrics are no longer active after cancelling metric activations.
    for (const int metricIndex : metricIndicesWithCanceledActivations) {
        const sp<MetricProducer>& metric = mAllMetricProducers[metricIndex];
        metric->flushIfExpire(eventTimeNs);
        if (!metric->isActive()) {
            activeMetricsIndices.erase(metricIndex);
        }
    }

    isActive |= !activeMetricsIndices.empty();


    // Determine which metric activations should be turned on and turn them on
    for (const auto& it : mActivationAtomTrackerToMetricMap) {
        if (matcherCache[it.first] == MatchingState::kMatched) {
            for (int metricIndex : it.second) {
                mAllMetricProducers[metricIndex]->activate(it.first, eventTimeNs);
                isActive |= mAllMetricProducers[metricIndex]->isActive();
            }
        }
    }

    mIsActive = isActive;

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
    }

    for (size_t i = 0; i < mAllConditionTrackers.size(); i++) {
        if (changedCache[i] == false) {
            continue;
        }
        auto pair = mConditionToMetricMap.find(i);
        if (pair != mConditionToMetricMap.end()) {
            auto& metricList = pair->second;
            for (auto metricIndex : metricList) {
                // Metric cares about non sliced condition, and it's changed.
                // Push the new condition to it directly.
                if (!mAllMetricProducers[metricIndex]->isConditionSliced()) {
                    mAllMetricProducers[metricIndex]->onConditionChanged(conditionCache[i],
                                                                         eventTimeNs);
                    // Metric cares about sliced conditions, and it may have changed. Send
                    // notification, and the metric can query the sliced conditions that are
                    // interesting to it.
                } else {
                    mAllMetricProducers[metricIndex]->onSlicedConditionMayChange(conditionCache[i],
                                                                                 eventTimeNs);
                }
            }
        }
    }

    // For matched AtomMatchers, tell relevant metrics that a matched event has come.
    for (size_t i = 0; i < mAllAtomMatchers.size(); i++) {
        if (matcherCache[i] == MatchingState::kMatched) {
            StatsdStats::getInstance().noteMatcherMatched(mConfigKey,
                                                          mAllAtomMatchers[i]->getId());
            auto pair = mTrackerToMetricMap.find(i);
            if (pair != mTrackerToMetricMap.end()) {
                auto& metricList = pair->second;
                for (const int metricIndex : metricList) {
                    // pushed metrics are never scheduled pulls
                    mAllMetricProducers[metricIndex]->onMatchedLogEvent(i, event);
                }
            }
        }
    }
}

void MetricsManager::onAnomalyAlarmFired(
        const int64_t& timestampNs,
        unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>>& alarmSet) {
    for (const auto& itr : mAllAnomalyTrackers) {
        itr->informAlarmsFired(timestampNs, alarmSet);
    }
}

void MetricsManager::onPeriodicAlarmFired(
        const int64_t& timestampNs,
        unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>>& alarmSet) {
    for (const auto& itr : mAllPeriodicAlarmTrackers) {
        itr->informAlarmsFired(timestampNs, alarmSet);
    }
}

// Returns the total byte size of all metrics managed by a single config source.
size_t MetricsManager::byteSize() {
    size_t totalSize = 0;
    for (const auto& metricProducer : mAllMetricProducers) {
        totalSize += metricProducer->byteSize();
    }
    return totalSize;
}

void MetricsManager::loadActiveConfig(const ActiveConfig& config, int64_t currentTimeNs) {
    if (config.metric_size() == 0) {
        ALOGW("No active metric for config %s", mConfigKey.ToString().c_str());
        return;
    }

    for (int i = 0; i < config.metric_size(); i++) {
        const auto& activeMetric = config.metric(i);
        for (int metricIndex : mMetricIndexesWithActivation) {
            const auto& metric = mAllMetricProducers[metricIndex];
            if (metric->getMetricId() == activeMetric.id()) {
                VLOG("Setting active metric: %lld", (long long)metric->getMetricId());
                metric->loadActiveMetric(activeMetric, currentTimeNs);
                if (!mIsActive && metric->isActive()) {
                    StatsdStats::getInstance().noteActiveStatusChanged(mConfigKey,
                                                                       /*activate=*/ true);
                }
                mIsActive |= metric->isActive();
            }
        }
    }
}

void MetricsManager::writeActiveConfigToProtoOutputStream(
        int64_t currentTimeNs, const DumpReportReason reason, ProtoOutputStream* proto) {
    proto->write(FIELD_TYPE_INT64 | FIELD_ID_ACTIVE_CONFIG_ID, (long long)mConfigKey.GetId());
    proto->write(FIELD_TYPE_INT32 | FIELD_ID_ACTIVE_CONFIG_UID, mConfigKey.GetUid());
    for (int metricIndex : mMetricIndexesWithActivation) {
        const auto& metric = mAllMetricProducers[metricIndex];
        const uint64_t metricToken = proto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED |
                FIELD_ID_ACTIVE_CONFIG_METRIC);
        metric->writeActiveMetricToProtoOutputStream(currentTimeNs, reason, proto);
        proto->end(metricToken);
    }
}




}  // namespace statsd
}  // namespace os
}  // namespace android
