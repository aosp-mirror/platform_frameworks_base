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
#include "statslog.h"

#include <android-base/file.h>
#include <dirent.h>
#include <frameworks/base/cmds/statsd/src/active_config_list.pb.h>
#include "StatsLogProcessor.h"
#include "android-base/stringprintf.h"
#include "external/StatsPullerManager.h"
#include "guardrail/StatsdStats.h"
#include "metrics/CountMetricProducer.h"
#include "stats_log_util.h"
#include "stats_util.h"
#include "storage/StorageManager.h"

#include <log/log_event_list.h>
#include <utils/Errors.h>
#include <utils/SystemClock.h>

using namespace android;
using android::base::StringPrintf;
using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_BOOL;
using android::util::FIELD_TYPE_FLOAT;
using android::util::FIELD_TYPE_INT32;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::FIELD_TYPE_STRING;
using android::util::ProtoOutputStream;
using std::make_unique;
using std::unique_ptr;
using std::vector;

namespace android {
namespace os {
namespace statsd {

// for ConfigMetricsReportList
const int FIELD_ID_CONFIG_KEY = 1;
const int FIELD_ID_REPORTS = 2;
// for ConfigKey
const int FIELD_ID_UID = 1;
const int FIELD_ID_ID = 2;
// for ConfigMetricsReport
// const int FIELD_ID_METRICS = 1; // written in MetricsManager.cpp
const int FIELD_ID_UID_MAP = 2;
const int FIELD_ID_LAST_REPORT_ELAPSED_NANOS = 3;
const int FIELD_ID_CURRENT_REPORT_ELAPSED_NANOS = 4;
const int FIELD_ID_LAST_REPORT_WALL_CLOCK_NANOS = 5;
const int FIELD_ID_CURRENT_REPORT_WALL_CLOCK_NANOS = 6;
const int FIELD_ID_DUMP_REPORT_REASON = 8;
const int FIELD_ID_STRINGS = 9;

// for ActiveConfigList
const int FIELD_ID_ACTIVE_CONFIG_LIST_CONFIG = 1;

#define NS_PER_HOUR 3600 * NS_PER_SEC

#define STATS_ACTIVE_METRIC_DIR "/data/misc/stats-active-metric"

// Cool down period for writing data to disk to avoid overwriting files.
#define WRITE_DATA_COOL_DOWN_SEC 5

StatsLogProcessor::StatsLogProcessor(const sp<UidMap>& uidMap,
                                     const sp<StatsPullerManager>& pullerManager,
                                     const sp<AlarmMonitor>& anomalyAlarmMonitor,
                                     const sp<AlarmMonitor>& periodicAlarmMonitor,
                                     const int64_t timeBaseNs,
                                     const std::function<bool(const ConfigKey&)>& sendBroadcast,
                                     const std::function<bool(
                                            const int&, const vector<int64_t>&)>& activateBroadcast)
    : mUidMap(uidMap),
      mPullerManager(pullerManager),
      mAnomalyAlarmMonitor(anomalyAlarmMonitor),
      mPeriodicAlarmMonitor(periodicAlarmMonitor),
      mSendBroadcast(sendBroadcast),
      mSendActivationBroadcast(activateBroadcast),
      mTimeBaseNs(timeBaseNs),
      mLargestTimestampSeen(0),
      mLastTimestampSeen(0) {
    mPullerManager->ForceClearPullerCache();
}

StatsLogProcessor::~StatsLogProcessor() {
}

static void flushProtoToBuffer(ProtoOutputStream& proto, vector<uint8_t>* outData) {
    outData->clear();
    outData->resize(proto.size());
    size_t pos = 0;
    sp<android::util::ProtoReader> reader = proto.data();
    while (reader->readBuffer() != NULL) {
        size_t toRead = reader->currentToRead();
        std::memcpy(&((*outData)[pos]), reader->readBuffer(), toRead);
        pos += toRead;
        reader->move(toRead);
    }
}

void StatsLogProcessor::onAnomalyAlarmFired(
        const int64_t& timestampNs,
        unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>> alarmSet) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    for (const auto& itr : mMetricsManagers) {
        itr.second->onAnomalyAlarmFired(timestampNs, alarmSet);
    }
}
void StatsLogProcessor::onPeriodicAlarmFired(
        const int64_t& timestampNs,
        unordered_set<sp<const InternalAlarm>, SpHash<InternalAlarm>> alarmSet) {

    std::lock_guard<std::mutex> lock(mMetricsMutex);
    for (const auto& itr : mMetricsManagers) {
        itr.second->onPeriodicAlarmFired(timestampNs, alarmSet);
    }
}

void updateUid(Value* value, int hostUid) {
    int uid = value->int_value;
    if (uid != hostUid) {
        value->setInt(hostUid);
    }
}

void StatsLogProcessor::mapIsolatedUidToHostUidIfNecessaryLocked(LogEvent* event) const {
    if (android::util::AtomsInfo::kAtomsWithAttributionChain.find(event->GetTagId()) !=
        android::util::AtomsInfo::kAtomsWithAttributionChain.end()) {
        for (auto& value : *(event->getMutableValues())) {
            if (value.mField.getPosAtDepth(0) > kAttributionField) {
                break;
            }
            if (isAttributionUidField(value)) {
                const int hostUid = mUidMap->getHostUidOrSelf(value.mValue.int_value);
                updateUid(&value.mValue, hostUid);
            }
        }
    } else {
        auto it = android::util::AtomsInfo::kAtomsWithUidField.find(event->GetTagId());
        if (it != android::util::AtomsInfo::kAtomsWithUidField.end()) {
            int uidField = it->second;  // uidField is the field number in proto,
                                        // starting from 1
            if (uidField > 0 && (int)event->getValues().size() >= uidField &&
                (event->getValues())[uidField - 1].mValue.getType() == INT) {
                Value& value = (*event->getMutableValues())[uidField - 1].mValue;
                const int hostUid = mUidMap->getHostUidOrSelf(value.int_value);
                updateUid(&value, hostUid);
            } else {
                ALOGE("Malformed log, uid not found. %s", event->ToString().c_str());
            }
        }
    }
}

void StatsLogProcessor::onIsolatedUidChangedEventLocked(const LogEvent& event) {
    status_t err = NO_ERROR, err2 = NO_ERROR, err3 = NO_ERROR;
    bool is_create = event.GetBool(3, &err);
    auto parent_uid = int(event.GetLong(1, &err2));
    auto isolated_uid = int(event.GetLong(2, &err3));
    if (err == NO_ERROR && err2 == NO_ERROR && err3 == NO_ERROR) {
        if (is_create) {
            mUidMap->assignIsolatedUid(isolated_uid, parent_uid);
        } else {
            mUidMap->removeIsolatedUid(isolated_uid);
        }
    } else {
        ALOGE("Failed to parse uid in the isolated uid change event.");
    }
}

void StatsLogProcessor::resetConfigs() {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    resetConfigsLocked(getElapsedRealtimeNs());
}

void StatsLogProcessor::resetConfigsLocked(const int64_t timestampNs) {
    std::vector<ConfigKey> configKeys;
    for (auto it = mMetricsManagers.begin(); it != mMetricsManagers.end(); it++) {
        configKeys.push_back(it->first);
    }
    resetConfigsLocked(timestampNs, configKeys);
}

void StatsLogProcessor::OnLogEvent(LogEvent* event) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);

#ifdef VERY_VERBOSE_PRINTING
    if (mPrintAllLogs) {
        ALOGI("%s", event->ToString().c_str());
    }
#endif
    const int64_t currentTimestampNs = event->GetElapsedTimestampNs();

    resetIfConfigTtlExpiredLocked(currentTimestampNs);

    StatsdStats::getInstance().noteAtomLogged(
        event->GetTagId(), event->GetElapsedTimestampNs() / NS_PER_SEC);

    // Hard-coded logic to update the isolated uid's in the uid-map.
    // The field numbers need to be currently updated by hand with atoms.proto
    if (event->GetTagId() == android::util::ISOLATED_UID_CHANGED) {
        onIsolatedUidChangedEventLocked(*event);
    }

    if (mMetricsManagers.empty()) {
        return;
    }

    int64_t curTimeSec = getElapsedRealtimeSec();
    if (curTimeSec - mLastPullerCacheClearTimeSec > StatsdStats::kPullerCacheClearIntervalSec) {
        mPullerManager->ClearPullerCacheIfNecessary(curTimeSec * NS_PER_SEC);
        mLastPullerCacheClearTimeSec = curTimeSec;
    }


    if (event->GetTagId() != android::util::ISOLATED_UID_CHANGED) {
        // Map the isolated uid to host uid if necessary.
        mapIsolatedUidToHostUidIfNecessaryLocked(event);
    }

    std::unordered_set<int> uidsWithActiveConfigsChanged;
    std::unordered_map<int, std::vector<int64_t>> activeConfigsPerUid;
    // pass the event to metrics managers.
    for (auto& pair : mMetricsManagers) {
        int uid = pair.first.GetUid();
        int64_t configId = pair.first.GetId();
        bool isPrevActive = pair.second->isActive();
        pair.second->onLogEvent(*event);
        bool isCurActive = pair.second->isActive();
        // Map all active configs by uid.
        if (isCurActive) {
            auto activeConfigs = activeConfigsPerUid.find(uid);
            if (activeConfigs != activeConfigsPerUid.end()) {
                activeConfigs->second.push_back(configId);
            } else {
                vector<int64_t> newActiveConfigs;
                newActiveConfigs.push_back(configId);
                activeConfigsPerUid[uid] = newActiveConfigs;
            }
        }
        // The activation state of this config changed.
        if (isPrevActive != isCurActive) {
            VLOG("Active status changed for uid  %d", uid);
            uidsWithActiveConfigsChanged.insert(uid);
            StatsdStats::getInstance().noteActiveStatusChanged(pair.first, isCurActive);
        }
        flushIfNecessaryLocked(event->GetElapsedTimestampNs(), pair.first, *(pair.second));
    }

    for (int uid : uidsWithActiveConfigsChanged) {
        // Send broadcast so that receivers can pull data.
        auto lastBroadcastTime = mLastActivationBroadcastTimes.find(uid);
        if (lastBroadcastTime != mLastActivationBroadcastTimes.end()) {
            if (currentTimestampNs - lastBroadcastTime->second <
                    StatsdStats::kMinActivationBroadcastPeriodNs) {
                StatsdStats::getInstance().noteActivationBroadcastGuardrailHit(uid);
                VLOG("StatsD would've sent an activation broadcast but the rate limit stopped us.");
                return;
            }
        }
        auto activeConfigs = activeConfigsPerUid.find(uid);
        if (activeConfigs != activeConfigsPerUid.end()) {
            if (mSendActivationBroadcast(uid, activeConfigs->second)) {
                VLOG("StatsD sent activation notice for uid %d", uid);
                mLastActivationBroadcastTimes[uid] = currentTimestampNs;
            }
        } else {
            std::vector<int64_t> emptyActiveConfigs;
            if (mSendActivationBroadcast(uid, emptyActiveConfigs)) {
                VLOG("StatsD sent EMPTY activation notice for uid %d", uid);
                mLastActivationBroadcastTimes[uid] = currentTimestampNs;
            }
        }
    }
}

void StatsLogProcessor::GetActiveConfigs(const int uid, vector<int64_t>& outActiveConfigs) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    GetActiveConfigsLocked(uid, outActiveConfigs);
}

void StatsLogProcessor::GetActiveConfigsLocked(const int uid, vector<int64_t>& outActiveConfigs) {
    outActiveConfigs.clear();
    for (auto& pair : mMetricsManagers) {
        if (pair.first.GetUid() == uid && pair.second->isActive()) {
            outActiveConfigs.push_back(pair.first.GetId());
        }
    }
}

void StatsLogProcessor::OnConfigUpdated(const int64_t timestampNs, const ConfigKey& key,
                                        const StatsdConfig& config) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    WriteDataToDiskLocked(key, timestampNs, CONFIG_UPDATED, NO_TIME_CONSTRAINTS);
    OnConfigUpdatedLocked(timestampNs, key, config);
}

void StatsLogProcessor::OnConfigUpdatedLocked(
        const int64_t timestampNs, const ConfigKey& key, const StatsdConfig& config) {
    VLOG("Updated configuration for key %s", key.ToString().c_str());
    sp<MetricsManager> newMetricsManager =
            new MetricsManager(key, config, mTimeBaseNs, timestampNs, mUidMap, mPullerManager,
                               mAnomalyAlarmMonitor, mPeriodicAlarmMonitor);
    if (newMetricsManager->isConfigValid()) {
        mUidMap->OnConfigUpdated(key);
        if (newMetricsManager->shouldAddUidMapListener()) {
            // We have to add listener after the MetricsManager is constructed because it's
            // not safe to create wp or sp from this pointer inside its constructor.
            mUidMap->addListener(newMetricsManager.get());
        }
        newMetricsManager->refreshTtl(timestampNs);
        mMetricsManagers[key] = newMetricsManager;
        VLOG("StatsdConfig valid");
    } else {
        // If there is any error in the config, don't use it.
        ALOGE("StatsdConfig NOT valid");
    }
}

size_t StatsLogProcessor::GetMetricsSize(const ConfigKey& key) const {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    auto it = mMetricsManagers.find(key);
    if (it == mMetricsManagers.end()) {
        ALOGW("Config source %s does not exist", key.ToString().c_str());
        return 0;
    }
    return it->second->byteSize();
}

void StatsLogProcessor::dumpStates(int out, bool verbose) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    FILE* fout = fdopen(out, "w");
    if (fout == NULL) {
        return;
    }
    fprintf(fout, "MetricsManager count: %lu\n", (unsigned long)mMetricsManagers.size());
    for (auto metricsManager : mMetricsManagers) {
        metricsManager.second->dumpStates(fout, verbose);
    }

    fclose(fout);
}

/*
 * onDumpReport dumps serialized ConfigMetricsReportList into proto.
 */
void StatsLogProcessor::onDumpReport(const ConfigKey& key, const int64_t dumpTimeStampNs,
                                     const bool include_current_partial_bucket,
                                     const bool erase_data,
                                     const DumpReportReason dumpReportReason,
                                     const DumpLatency dumpLatency,
                                     ProtoOutputStream* proto) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);

    // Start of ConfigKey.
    uint64_t configKeyToken = proto->start(FIELD_TYPE_MESSAGE | FIELD_ID_CONFIG_KEY);
    proto->write(FIELD_TYPE_INT32 | FIELD_ID_UID, key.GetUid());
    proto->write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)key.GetId());
    proto->end(configKeyToken);
    // End of ConfigKey.

    bool keepFile = false;
    auto it = mMetricsManagers.find(key);
    if (it != mMetricsManagers.end() && it->second->shouldPersistLocalHistory()) {
        keepFile = true;
    }

    // Then, check stats-data directory to see there's any file containing
    // ConfigMetricsReport from previous shutdowns to concatenate to reports.
    StorageManager::appendConfigMetricsReport(
            key, proto, erase_data && !keepFile /* should remove file after appending it */,
            dumpReportReason == ADB_DUMP /*if caller is adb*/);

    if (it != mMetricsManagers.end()) {
        // This allows another broadcast to be sent within the rate-limit period if we get close to
        // filling the buffer again soon.
        mLastBroadcastTimes.erase(key);

        vector<uint8_t> buffer;
        onConfigMetricsReportLocked(key, dumpTimeStampNs, include_current_partial_bucket,
                                    erase_data, dumpReportReason, dumpLatency,
                                    false /* is this data going to be saved on disk */, &buffer);
        proto->write(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_REPORTS,
                     reinterpret_cast<char*>(buffer.data()), buffer.size());
    } else {
        ALOGW("Config source %s does not exist", key.ToString().c_str());
    }
}

/*
 * onDumpReport dumps serialized ConfigMetricsReportList into outData.
 */
void StatsLogProcessor::onDumpReport(const ConfigKey& key, const int64_t dumpTimeStampNs,
                                     const bool include_current_partial_bucket,
                                     const bool erase_data,
                                     const DumpReportReason dumpReportReason,
                                     const DumpLatency dumpLatency,
                                     vector<uint8_t>* outData) {
    ProtoOutputStream proto;
    onDumpReport(key, dumpTimeStampNs, include_current_partial_bucket, erase_data,
                 dumpReportReason, dumpLatency, &proto);

    if (outData != nullptr) {
        flushProtoToBuffer(proto, outData);
        VLOG("output data size %zu", outData->size());
    }

    StatsdStats::getInstance().noteMetricsReportSent(key, proto.size());
}

/*
 * onConfigMetricsReportLocked dumps serialized ConfigMetricsReport into outData.
 */
void StatsLogProcessor::onConfigMetricsReportLocked(
        const ConfigKey& key, const int64_t dumpTimeStampNs,
        const bool include_current_partial_bucket, const bool erase_data,
        const DumpReportReason dumpReportReason, const DumpLatency dumpLatency,
        const bool dataSavedOnDisk, vector<uint8_t>* buffer) {
    // We already checked whether key exists in mMetricsManagers in
    // WriteDataToDisk.
    auto it = mMetricsManagers.find(key);
    if (it == mMetricsManagers.end()) {
        return;
    }
    int64_t lastReportTimeNs = it->second->getLastReportTimeNs();
    int64_t lastReportWallClockNs = it->second->getLastReportWallClockNs();

    std::set<string> str_set;

    ProtoOutputStream tempProto;
    // First, fill in ConfigMetricsReport using current data on memory, which
    // starts from filling in StatsLogReport's.
    it->second->onDumpReport(dumpTimeStampNs, include_current_partial_bucket, erase_data,
                             dumpLatency, &str_set, &tempProto);

    // Fill in UidMap if there is at least one metric to report.
    // This skips the uid map if it's an empty config.
    if (it->second->getNumMetrics() > 0) {
        uint64_t uidMapToken = tempProto.start(FIELD_TYPE_MESSAGE | FIELD_ID_UID_MAP);
        mUidMap->appendUidMap(
                dumpTimeStampNs, key, it->second->hashStringInReport() ? &str_set : nullptr,
                it->second->versionStringsInReport(), it->second->installerInReport(), &tempProto);
        tempProto.end(uidMapToken);
    }

    // Fill in the timestamps.
    tempProto.write(FIELD_TYPE_INT64 | FIELD_ID_LAST_REPORT_ELAPSED_NANOS,
                    (long long)lastReportTimeNs);
    tempProto.write(FIELD_TYPE_INT64 | FIELD_ID_CURRENT_REPORT_ELAPSED_NANOS,
                    (long long)dumpTimeStampNs);
    tempProto.write(FIELD_TYPE_INT64 | FIELD_ID_LAST_REPORT_WALL_CLOCK_NANOS,
                    (long long)lastReportWallClockNs);
    tempProto.write(FIELD_TYPE_INT64 | FIELD_ID_CURRENT_REPORT_WALL_CLOCK_NANOS,
                    (long long)getWallClockNs());
    // Dump report reason
    tempProto.write(FIELD_TYPE_INT32 | FIELD_ID_DUMP_REPORT_REASON, dumpReportReason);

    for (const auto& str : str_set) {
        tempProto.write(FIELD_TYPE_STRING | FIELD_COUNT_REPEATED | FIELD_ID_STRINGS, str);
    }

    flushProtoToBuffer(tempProto, buffer);

    // save buffer to disk if needed
    if (erase_data && !dataSavedOnDisk && it->second->shouldPersistLocalHistory()) {
        VLOG("save history to disk");
        string file_name = StorageManager::getDataHistoryFileName((long)getWallClockSec(),
                                                                  key.GetUid(), key.GetId());
        StorageManager::writeFile(file_name.c_str(), buffer->data(), buffer->size());
    }
}

void StatsLogProcessor::resetConfigsLocked(const int64_t timestampNs,
                                           const std::vector<ConfigKey>& configs) {
    for (const auto& key : configs) {
        StatsdConfig config;
        if (StorageManager::readConfigFromDisk(key, &config)) {
            OnConfigUpdatedLocked(timestampNs, key, config);
            StatsdStats::getInstance().noteConfigReset(key);
        } else {
            ALOGE("Failed to read backup config from disk for : %s", key.ToString().c_str());
            auto it = mMetricsManagers.find(key);
            if (it != mMetricsManagers.end()) {
                it->second->refreshTtl(timestampNs);
            }
        }
    }
}

void StatsLogProcessor::resetIfConfigTtlExpiredLocked(const int64_t timestampNs) {
    std::vector<ConfigKey> configKeysTtlExpired;
    for (auto it = mMetricsManagers.begin(); it != mMetricsManagers.end(); it++) {
        if (it->second != nullptr && !it->second->isInTtl(timestampNs)) {
            configKeysTtlExpired.push_back(it->first);
        }
    }
    if (configKeysTtlExpired.size() > 0) {
        WriteDataToDiskLocked(CONFIG_RESET, NO_TIME_CONSTRAINTS);
        resetConfigsLocked(timestampNs, configKeysTtlExpired);
    }
}

void StatsLogProcessor::OnConfigRemoved(const ConfigKey& key) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    auto it = mMetricsManagers.find(key);
    if (it != mMetricsManagers.end()) {
        WriteDataToDiskLocked(key, getElapsedRealtimeNs(), CONFIG_REMOVED,
                              NO_TIME_CONSTRAINTS);
        mMetricsManagers.erase(it);
        mUidMap->OnConfigRemoved(key);
    }
    StatsdStats::getInstance().noteConfigRemoved(key);

    mLastBroadcastTimes.erase(key);

    int uid = key.GetUid();
    bool lastConfigForUid = true;
    for (auto it : mMetricsManagers) {
        if (it.first.GetUid() == uid) {
            lastConfigForUid = false;
            break;
        }
    }
    if (lastConfigForUid) {
        mLastActivationBroadcastTimes.erase(uid);
    }

    if (mMetricsManagers.empty()) {
        mPullerManager->ForceClearPullerCache();
    }
}

void StatsLogProcessor::flushIfNecessaryLocked(
    int64_t timestampNs, const ConfigKey& key, MetricsManager& metricsManager) {
    auto lastCheckTime = mLastByteSizeTimes.find(key);
    if (lastCheckTime != mLastByteSizeTimes.end()) {
        if (timestampNs - lastCheckTime->second < StatsdStats::kMinByteSizeCheckPeriodNs) {
            return;
        }
    }

    // We suspect that the byteSize() computation is expensive, so we set a rate limit.
    size_t totalBytes = metricsManager.byteSize();
    mLastByteSizeTimes[key] = timestampNs;
    bool requestDump = false;
    if (totalBytes >
        StatsdStats::kMaxMetricsBytesPerConfig) {  // Too late. We need to start clearing data.
        metricsManager.dropData(timestampNs);
        StatsdStats::getInstance().noteDataDropped(key, totalBytes);
        VLOG("StatsD had to toss out metrics for %s", key.ToString().c_str());
    } else if ((totalBytes > StatsdStats::kBytesPerConfigTriggerGetData) ||
               (mOnDiskDataConfigs.find(key) != mOnDiskDataConfigs.end())) {
        // Request to send a broadcast if:
        // 1. in memory data > threshold   OR
        // 2. config has old data report on disk.
        requestDump = true;
    }

    if (requestDump) {
        // Send broadcast so that receivers can pull data.
        auto lastBroadcastTime = mLastBroadcastTimes.find(key);
        if (lastBroadcastTime != mLastBroadcastTimes.end()) {
            if (timestampNs - lastBroadcastTime->second < StatsdStats::kMinBroadcastPeriodNs) {
                VLOG("StatsD would've sent a broadcast but the rate limit stopped us.");
                return;
            }
        }
        if (mSendBroadcast(key)) {
            mOnDiskDataConfigs.erase(key);
            VLOG("StatsD triggered data fetch for %s", key.ToString().c_str());
            mLastBroadcastTimes[key] = timestampNs;
            StatsdStats::getInstance().noteBroadcastSent(key);
        }
    }
}

void StatsLogProcessor::WriteDataToDiskLocked(const ConfigKey& key,
                                              const int64_t timestampNs,
                                              const DumpReportReason dumpReportReason,
                                              const DumpLatency dumpLatency) {
    if (mMetricsManagers.find(key) == mMetricsManagers.end() ||
        !mMetricsManagers.find(key)->second->shouldWriteToDisk()) {
        return;
    }
    vector<uint8_t> buffer;
    onConfigMetricsReportLocked(key, timestampNs, true /* include_current_partial_bucket*/,
                                true /* erase_data */, dumpReportReason, dumpLatency, true,
                                &buffer);
    string file_name =
            StorageManager::getDataFileName((long)getWallClockSec(), key.GetUid(), key.GetId());
    StorageManager::writeFile(file_name.c_str(), buffer.data(), buffer.size());

    // We were able to write the ConfigMetricsReport to disk, so we should trigger collection ASAP.
    mOnDiskDataConfigs.insert(key);
}

void StatsLogProcessor::SaveActiveConfigsToDisk(int64_t currentTimeNs) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    const int64_t timeNs = getElapsedRealtimeNs();
    // Do not write to disk if we already have in the last few seconds.
    if (static_cast<unsigned long long> (timeNs) <
            mLastActiveMetricsWriteNs + WRITE_DATA_COOL_DOWN_SEC * NS_PER_SEC) {
        ALOGI("Statsd skipping writing active metrics to disk. Already wrote data in last %d seconds",
                WRITE_DATA_COOL_DOWN_SEC);
        return;
    }
    mLastActiveMetricsWriteNs = timeNs;

    ProtoOutputStream proto;
    WriteActiveConfigsToProtoOutputStreamLocked(currentTimeNs, DEVICE_SHUTDOWN, &proto);

    string file_name = StringPrintf("%s/active_metrics", STATS_ACTIVE_METRIC_DIR);
    StorageManager::deleteFile(file_name.c_str());
    android::base::unique_fd fd(
            open(file_name.c_str(), O_WRONLY | O_CREAT | O_CLOEXEC, S_IRUSR | S_IWUSR));
    if (fd == -1) {
        ALOGE("Attempt to write %s but failed", file_name.c_str());
        return;
    }
    proto.flush(fd.get());
}

void StatsLogProcessor::WriteActiveConfigsToProtoOutputStream(
        int64_t currentTimeNs, const DumpReportReason reason, ProtoOutputStream* proto) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    WriteActiveConfigsToProtoOutputStreamLocked(currentTimeNs, reason, proto);
}

void StatsLogProcessor::WriteActiveConfigsToProtoOutputStreamLocked(
        int64_t currentTimeNs,  const DumpReportReason reason, ProtoOutputStream* proto) {
    for (const auto& pair : mMetricsManagers) {
        const sp<MetricsManager>& metricsManager = pair.second;
        uint64_t configToken = proto->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED |
                                                     FIELD_ID_ACTIVE_CONFIG_LIST_CONFIG);
        metricsManager->writeActiveConfigToProtoOutputStream(currentTimeNs, reason, proto);
        proto->end(configToken);
    }
}
void StatsLogProcessor::LoadActiveConfigsFromDisk() {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    string file_name = StringPrintf("%s/active_metrics", STATS_ACTIVE_METRIC_DIR);
    int fd = open(file_name.c_str(), O_RDONLY | O_CLOEXEC);
    if (-1 == fd) {
        VLOG("Attempt to read %s but failed", file_name.c_str());
        StorageManager::deleteFile(file_name.c_str());
        return;
    }
    string content;
    if (!android::base::ReadFdToString(fd, &content)) {
        ALOGE("Attempt to read %s but failed", file_name.c_str());
        close(fd);
        StorageManager::deleteFile(file_name.c_str());
        return;
    }

    close(fd);

    ActiveConfigList activeConfigList;
    if (!activeConfigList.ParseFromString(content)) {
        ALOGE("Attempt to read %s but failed; failed to load active configs", file_name.c_str());
        StorageManager::deleteFile(file_name.c_str());
        return;
    }
    // Passing in mTimeBaseNs only works as long as we only load from disk is when statsd starts.
    SetConfigsActiveStateLocked(activeConfigList, mTimeBaseNs);
    StorageManager::deleteFile(file_name.c_str());
}

void StatsLogProcessor::SetConfigsActiveState(const ActiveConfigList& activeConfigList,
                                                    int64_t currentTimeNs) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    SetConfigsActiveStateLocked(activeConfigList, currentTimeNs);
}

void StatsLogProcessor::SetConfigsActiveStateLocked(const ActiveConfigList& activeConfigList,
                                                    int64_t currentTimeNs) {
    for (int i = 0; i < activeConfigList.config_size(); i++) {
        const auto& config = activeConfigList.config(i);
        ConfigKey key(config.uid(), config.id());
        auto it = mMetricsManagers.find(key);
        if (it == mMetricsManagers.end()) {
            ALOGE("No config found for config %s", key.ToString().c_str());
            continue;
        }
        VLOG("Setting active config %s", key.ToString().c_str());
        it->second->loadActiveConfig(config, currentTimeNs);
    }
    VLOG("Successfully loaded %d active configs.", activeConfigList.config_size());
}

void StatsLogProcessor::WriteDataToDiskLocked(const DumpReportReason dumpReportReason,
                                              const DumpLatency dumpLatency) {
    const int64_t timeNs = getElapsedRealtimeNs();
    // Do not write to disk if we already have in the last few seconds.
    // This is to avoid overwriting files that would have the same name if we
    //   write twice in the same second.
    if (static_cast<unsigned long long> (timeNs) <
            mLastWriteTimeNs + WRITE_DATA_COOL_DOWN_SEC * NS_PER_SEC) {
        ALOGI("Statsd skipping writing data to disk. Already wrote data in last %d seconds",
                WRITE_DATA_COOL_DOWN_SEC);
        return;
    }
    mLastWriteTimeNs = timeNs;
    for (auto& pair : mMetricsManagers) {
        WriteDataToDiskLocked(pair.first, timeNs, dumpReportReason, dumpLatency);
    }
}

void StatsLogProcessor::WriteDataToDisk(const DumpReportReason dumpReportReason,
                                        const DumpLatency dumpLatency) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    WriteDataToDiskLocked(dumpReportReason, dumpLatency);
}

void StatsLogProcessor::informPullAlarmFired(const int64_t timestampNs) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    mPullerManager->OnAlarmFired(timestampNs);
}

int64_t StatsLogProcessor::getLastReportTimeNs(const ConfigKey& key) {
    auto it = mMetricsManagers.find(key);
    if (it == mMetricsManagers.end()) {
        return 0;
    } else {
        return it->second->getLastReportTimeNs();
    }
}

void StatsLogProcessor::noteOnDiskData(const ConfigKey& key) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    mOnDiskDataConfigs.insert(key);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
