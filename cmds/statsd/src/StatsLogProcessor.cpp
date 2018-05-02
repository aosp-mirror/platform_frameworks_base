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
#include "statslog.h"

#include <android-base/file.h>
#include <dirent.h>
#include "StatsLogProcessor.h"
#include "stats_log_util.h"
#include "android-base/stringprintf.h"
#include "guardrail/StatsdStats.h"
#include "metrics/CountMetricProducer.h"
#include "external/StatsPullerManager.h"
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

#define NS_PER_HOUR 3600 * NS_PER_SEC

#define STATS_DATA_DIR "/data/misc/stats-data"

StatsLogProcessor::StatsLogProcessor(const sp<UidMap>& uidMap,
                                     const sp<AlarmMonitor>& anomalyAlarmMonitor,
                                     const sp<AlarmMonitor>& periodicAlarmMonitor,
                                     const int64_t timeBaseNs,
                                     const std::function<void(const ConfigKey&)>& sendBroadcast)
    : mUidMap(uidMap),
      mAnomalyAlarmMonitor(anomalyAlarmMonitor),
      mPeriodicAlarmMonitor(periodicAlarmMonitor),
      mSendBroadcast(sendBroadcast),
      mTimeBaseNs(timeBaseNs),
      mLargestTimestampSeen(0),
      mLastTimestampSeen(0) {
}

StatsLogProcessor::~StatsLogProcessor() {
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
            mUidMap->removeIsolatedUid(isolated_uid, parent_uid);
        }
    } else {
        ALOGE("Failed to parse uid in the isolated uid change event.");
    }
}

void StatsLogProcessor::OnLogEvent(LogEvent* event) {
    OnLogEvent(event, false);
}

void StatsLogProcessor::OnLogEvent(LogEvent* event, bool reconnected) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);

#ifdef VERY_VERBOSE_PRINTING
    if (mPrintAllLogs) {
        ALOGI("%s", event->ToString().c_str());
    }
#endif
    const int64_t currentTimestampNs = event->GetElapsedTimestampNs();

    if (reconnected && mLastTimestampSeen != 0) {
        // LogReader tells us the connection has just been reset. Now we need
        // to enter reconnection state to find the last CP.
        mInReconnection = true;
    }

    if (mInReconnection) {
        // We see the checkpoint
        if (currentTimestampNs == mLastTimestampSeen) {
            mInReconnection = false;
            // Found the CP. ignore this event, and we will start to read from next event.
            return;
        }
        if (currentTimestampNs > mLargestTimestampSeen) {
            // We see a new log but CP has not been found yet. Give up now.
            mLogLossCount++;
            mInReconnection = false;
            StatsdStats::getInstance().noteLogLost(currentTimestampNs);
            // Persist the data before we reset. Do we want this?
            WriteDataToDiskLocked(CONFIG_RESET);
            // We see fresher event before we see the checkpoint. We might have lost data.
            // The best we can do is to reset.
            std::vector<ConfigKey> configKeys;
            for (auto it = mMetricsManagers.begin(); it != mMetricsManagers.end(); it++) {
                configKeys.push_back(it->first);
            }
            resetConfigsLocked(currentTimestampNs, configKeys);
        } else {
            // Still in search of the CP. Keep going.
            return;
        }
    }

    mLogCount++;
    mLastTimestampSeen = currentTimestampNs;
    if (mLargestTimestampSeen < currentTimestampNs) {
        mLargestTimestampSeen = currentTimestampNs;
    }

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
        mStatsPullerManager.ClearPullerCacheIfNecessary(curTimeSec * NS_PER_SEC);
        mLastPullerCacheClearTimeSec = curTimeSec;
    }


    if (event->GetTagId() != android::util::ISOLATED_UID_CHANGED) {
        // Map the isolated uid to host uid if necessary.
        mapIsolatedUidToHostUidIfNecessaryLocked(event);
    }

    // pass the event to metrics managers.
    for (auto& pair : mMetricsManagers) {
        pair.second->onLogEvent(*event);
        flushIfNecessaryLocked(event->GetElapsedTimestampNs(), pair.first, *(pair.second));
    }
}

void StatsLogProcessor::OnConfigUpdated(const int64_t timestampNs, const ConfigKey& key,
                                        const StatsdConfig& config) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    OnConfigUpdatedLocked(timestampNs, key, config);
}

void StatsLogProcessor::OnConfigUpdatedLocked(
        const int64_t timestampNs, const ConfigKey& key, const StatsdConfig& config) {
    VLOG("Updated configuration for key %s", key.ToString().c_str());
    sp<MetricsManager> newMetricsManager =
        new MetricsManager(key, config, mTimeBaseNs, timestampNs, mUidMap,
                           mAnomalyAlarmMonitor, mPeriodicAlarmMonitor);
    auto it = mMetricsManagers.find(key);
    if (it != mMetricsManagers.end()) {
        WriteDataToDiskLocked(it->first, CONFIG_UPDATED);
    }
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

void StatsLogProcessor::dumpStates(FILE* out, bool verbose) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    fprintf(out, "MetricsManager count: %lu\n", (unsigned long)mMetricsManagers.size());
    for (auto metricsManager : mMetricsManagers) {
        metricsManager.second->dumpStates(out, verbose);
    }
}

/*
 * onDumpReport dumps serialized ConfigMetricsReportList into outData.
 */
void StatsLogProcessor::onDumpReport(const ConfigKey& key, const int64_t dumpTimeStampNs,
                                     const bool include_current_partial_bucket,
                                     const bool include_string,
                                     const DumpReportReason dumpReportReason,
                                     vector<uint8_t>* outData) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);

    ProtoOutputStream proto;

    // Start of ConfigKey.
    uint64_t configKeyToken = proto.start(FIELD_TYPE_MESSAGE | FIELD_ID_CONFIG_KEY);
    proto.write(FIELD_TYPE_INT32 | FIELD_ID_UID, key.GetUid());
    proto.write(FIELD_TYPE_INT64 | FIELD_ID_ID, (long long)key.GetId());
    proto.end(configKeyToken);
    // End of ConfigKey.

    // Then, check stats-data directory to see there's any file containing
    // ConfigMetricsReport from previous shutdowns to concatenate to reports.
    StorageManager::appendConfigMetricsReport(key, &proto);

    auto it = mMetricsManagers.find(key);
    if (it != mMetricsManagers.end()) {
        // This allows another broadcast to be sent within the rate-limit period if we get close to
        // filling the buffer again soon.
        mLastBroadcastTimes.erase(key);

        // Start of ConfigMetricsReport (reports).
        uint64_t reportsToken =
                proto.start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_REPORTS);
        onConfigMetricsReportLocked(key, dumpTimeStampNs, include_current_partial_bucket,
                                    include_string, dumpReportReason, &proto);
        proto.end(reportsToken);
        // End of ConfigMetricsReport (reports).
    } else {
        ALOGW("Config source %s does not exist", key.ToString().c_str());
    }

    if (outData != nullptr) {
        outData->clear();
        outData->resize(proto.size());
        size_t pos = 0;
        auto iter = proto.data();
        while (iter.readBuffer() != NULL) {
            size_t toRead = iter.currentToRead();
            std::memcpy(&((*outData)[pos]), iter.readBuffer(), toRead);
            pos += toRead;
            iter.rp()->move(toRead);
        }
    }

    StatsdStats::getInstance().noteMetricsReportSent(key, proto.size());
}

/*
 * onConfigMetricsReportLocked dumps serialized ConfigMetricsReport into outData.
 */
void StatsLogProcessor::onConfigMetricsReportLocked(const ConfigKey& key,
                                                    const int64_t dumpTimeStampNs,
                                                    const bool include_current_partial_bucket,
                                                    const bool include_string,
                                                    const DumpReportReason dumpReportReason,
                                                    ProtoOutputStream* proto) {
    // We already checked whether key exists in mMetricsManagers in
    // WriteDataToDisk.
    auto it = mMetricsManagers.find(key);
    if (it == mMetricsManagers.end()) {
        return;
    }
    int64_t lastReportTimeNs = it->second->getLastReportTimeNs();
    int64_t lastReportWallClockNs = it->second->getLastReportWallClockNs();

    std::set<string> str_set;

    // First, fill in ConfigMetricsReport using current data on memory, which
    // starts from filling in StatsLogReport's.
    it->second->onDumpReport(dumpTimeStampNs, include_current_partial_bucket,
                             &str_set, proto);

    // Fill in UidMap.
    uint64_t uidMapToken = proto->start(FIELD_TYPE_MESSAGE | FIELD_ID_UID_MAP);
    mUidMap->appendUidMap(dumpTimeStampNs, key, &str_set, proto);
    proto->end(uidMapToken);

    // Fill in the timestamps.
    proto->write(FIELD_TYPE_INT64 | FIELD_ID_LAST_REPORT_ELAPSED_NANOS,
                (long long)lastReportTimeNs);
    proto->write(FIELD_TYPE_INT64 | FIELD_ID_CURRENT_REPORT_ELAPSED_NANOS,
                (long long)dumpTimeStampNs);
    proto->write(FIELD_TYPE_INT64 | FIELD_ID_LAST_REPORT_WALL_CLOCK_NANOS,
                (long long)lastReportWallClockNs);
    proto->write(FIELD_TYPE_INT64 | FIELD_ID_CURRENT_REPORT_WALL_CLOCK_NANOS,
                (long long)getWallClockNs());
    // Dump report reason
    proto->write(FIELD_TYPE_INT32 | FIELD_ID_DUMP_REPORT_REASON, dumpReportReason);

    if (include_string) {
        for (const auto& str : str_set) {
            proto->write(FIELD_TYPE_STRING | FIELD_COUNT_REPEATED | FIELD_ID_STRINGS, str);
        }
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
        resetConfigsLocked(timestampNs, configKeysTtlExpired);
    }
}

void StatsLogProcessor::OnConfigRemoved(const ConfigKey& key) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    auto it = mMetricsManagers.find(key);
    if (it != mMetricsManagers.end()) {
        WriteDataToDiskLocked(key, CONFIG_REMOVED);
        mMetricsManagers.erase(it);
        mUidMap->OnConfigRemoved(key);
    }
    StatsdStats::getInstance().noteConfigRemoved(key);

    mLastBroadcastTimes.erase(key);

    if (mMetricsManagers.empty()) {
        mStatsPullerManager.ForceClearPullerCache();
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
    if (totalBytes >
        StatsdStats::kMaxMetricsBytesPerConfig) {  // Too late. We need to start clearing data.
        metricsManager.dropData(timestampNs);
        StatsdStats::getInstance().noteDataDropped(key);
        VLOG("StatsD had to toss out metrics for %s", key.ToString().c_str());
    } else if (totalBytes > StatsdStats::kBytesPerConfigTriggerGetData) {
        // Send broadcast so that receivers can pull data.
        auto lastBroadcastTime = mLastBroadcastTimes.find(key);
        if (lastBroadcastTime != mLastBroadcastTimes.end()) {
            if (timestampNs - lastBroadcastTime->second < StatsdStats::kMinBroadcastPeriodNs) {
                VLOG("StatsD would've sent a broadcast but the rate limit stopped us.");
                return;
            }
        }
        mLastBroadcastTimes[key] = timestampNs;
        VLOG("StatsD requesting broadcast for %s", key.ToString().c_str());
        mSendBroadcast(key);
        StatsdStats::getInstance().noteBroadcastSent(key);
    }
}

void StatsLogProcessor::WriteDataToDiskLocked(const ConfigKey& key,
                                              const DumpReportReason dumpReportReason) {
    ProtoOutputStream proto;
    onConfigMetricsReportLocked(key, getElapsedRealtimeNs(),
                                true /* include_current_partial_bucket*/,
                                false /* include strings */, dumpReportReason, &proto);
    string file_name = StringPrintf("%s/%ld_%d_%lld", STATS_DATA_DIR,
         (long)getWallClockSec(), key.GetUid(), (long long)key.GetId());
    android::base::unique_fd fd(open(file_name.c_str(),
                                O_WRONLY | O_CREAT | O_CLOEXEC, S_IRUSR | S_IWUSR));
    if (fd == -1) {
        ALOGE("Attempt to write %s but failed", file_name.c_str());
        return;
    }
    proto.flush(fd.get());
}

void StatsLogProcessor::WriteDataToDiskLocked(const DumpReportReason dumpReportReason) {
    for (auto& pair : mMetricsManagers) {
        WriteDataToDiskLocked(pair.first, dumpReportReason);
    }
}

void StatsLogProcessor::WriteDataToDisk(bool isShutdown) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    WriteDataToDiskLocked(DEVICE_SHUTDOWN);
}

void StatsLogProcessor::informPullAlarmFired(const int64_t timestampNs) {
    std::lock_guard<std::mutex> lock(mMetricsMutex);
    mStatsPullerManager.OnAlarmFired(timestampNs);
}

int64_t StatsLogProcessor::getLastReportTimeNs(const ConfigKey& key) {
    auto it = mMetricsManagers.find(key);
    if (it == mMetricsManagers.end()) {
        return 0;
    } else {
        return it->second->getLastReportTimeNs();
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
