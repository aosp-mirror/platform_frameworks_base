/*
 * Copyright 2017, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "StatsdStats.h"

#include <android/util/ProtoOutputStream.h>
#include "../stats_log_util.h"
#include "statslog.h"

namespace android {
namespace os {
namespace statsd {

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_BOOL;
using android::util::FIELD_TYPE_FLOAT;
using android::util::FIELD_TYPE_INT32;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::FIELD_TYPE_STRING;
using android::util::ProtoOutputStream;
using std::lock_guard;
using std::map;
using std::string;
using std::vector;

const int FIELD_ID_BEGIN_TIME = 1;
const int FIELD_ID_END_TIME = 2;
const int FIELD_ID_CONFIG_STATS = 3;
const int FIELD_ID_ATOM_STATS = 7;
const int FIELD_ID_UIDMAP_STATS = 8;
const int FIELD_ID_ANOMALY_ALARM_STATS = 9;
// const int FIELD_ID_PULLED_ATOM_STATS = 10; // The proto is written in stats_log_util.cpp
const int FIELD_ID_LOGGER_ERROR_STATS = 11;
const int FIELD_ID_SUBSCRIBER_ALARM_STATS = 12;

const int FIELD_ID_ATOM_STATS_TAG = 1;
const int FIELD_ID_ATOM_STATS_COUNT = 2;

const int FIELD_ID_ANOMALY_ALARMS_REGISTERED = 1;
const int FIELD_ID_SUBSCRIBER_ALARMS_REGISTERED = 1;

const int FIELD_ID_LOGGER_STATS_TIME = 1;
const int FIELD_ID_LOGGER_STATS_ERROR_CODE = 2;

std::map<int, long> StatsdStats::kPullerCooldownMap = {
        {android::util::KERNEL_WAKELOCK, 1},
        {android::util::WIFI_BYTES_TRANSFER, 1},
        {android::util::MOBILE_BYTES_TRANSFER, 1},
        {android::util::WIFI_BYTES_TRANSFER_BY_FG_BG, 1},
        {android::util::MOBILE_BYTES_TRANSFER_BY_FG_BG, 1},
        {android::util::SUBSYSTEM_SLEEP_STATE, 1},
        {android::util::CPU_TIME_PER_FREQ, 1},
        {android::util::CPU_TIME_PER_UID, 1},
        {android::util::CPU_TIME_PER_UID_FREQ, 1},
};

// TODO: add stats for pulled atoms.
StatsdStats::StatsdStats() {
    mPushedAtomStats.resize(android::util::kMaxPushedAtomId + 1);
    mStartTimeSec = getWallClockSec();
}

StatsdStats& StatsdStats::getInstance() {
    static StatsdStats statsInstance;
    return statsInstance;
}

void StatsdStats::addToIceBoxLocked(const StatsdStatsReport_ConfigStats& stats) {
    // The size of mIceBox grows strictly by one at a time. It won't be > kMaxIceBoxSize.
    if (mIceBox.size() == kMaxIceBoxSize) {
        mIceBox.pop_front();
    }
    mIceBox.push_back(stats);
}

void StatsdStats::noteConfigReceived(const ConfigKey& key, int metricsCount, int conditionsCount,
                                     int matchersCount, int alertsCount, bool isValid) {
    lock_guard<std::mutex> lock(mLock);
    int32_t nowTimeSec = getWallClockSec();

    // If there is an existing config for the same key, icebox the old config.
    noteConfigRemovedInternalLocked(key);

    StatsdStatsReport_ConfigStats configStats;
    configStats.set_uid(key.GetUid());
    configStats.set_id(key.GetId());
    configStats.set_creation_time_sec(nowTimeSec);
    configStats.set_metric_count(metricsCount);
    configStats.set_condition_count(conditionsCount);
    configStats.set_matcher_count(matchersCount);
    configStats.set_alert_count(alertsCount);
    configStats.set_is_valid(isValid);

    if (isValid) {
        mConfigStats[key] = configStats;
    } else {
        configStats.set_deletion_time_sec(nowTimeSec);
        addToIceBoxLocked(configStats);
    }
}

void StatsdStats::noteConfigRemovedInternalLocked(const ConfigKey& key) {
    auto it = mConfigStats.find(key);
    if (it != mConfigStats.end()) {
        int32_t nowTimeSec = getWallClockSec();
        it->second.set_deletion_time_sec(nowTimeSec);
        // Add condition stats, metrics stats, matcher stats, alert stats
        addSubStatsToConfigLocked(key, it->second);
        // Remove them after they are added to the config stats.
        mMatcherStats.erase(key);
        mMetricsStats.erase(key);
        mAlertStats.erase(key);
        mConditionStats.erase(key);
        addToIceBoxLocked(it->second);
        mConfigStats.erase(it);
    }
}

void StatsdStats::noteConfigRemoved(const ConfigKey& key) {
    lock_guard<std::mutex> lock(mLock);
    noteConfigRemovedInternalLocked(key);
}

void StatsdStats::noteBroadcastSent(const ConfigKey& key) {
    noteBroadcastSent(key, getWallClockSec());
}

void StatsdStats::noteBroadcastSent(const ConfigKey& key, int32_t timeSec) {
    lock_guard<std::mutex> lock(mLock);
    auto it = mConfigStats.find(key);
    if (it == mConfigStats.end()) {
        ALOGE("Config key %s not found!", key.ToString().c_str());
        return;
    }
    if (it->second.broadcast_sent_time_sec_size() >= kMaxTimestampCount) {
        auto timestampList = it->second.mutable_broadcast_sent_time_sec();
        // This is O(N) operation. It shouldn't happen often, and N is only 20.
        timestampList->erase(timestampList->begin());
    }
    it->second.add_broadcast_sent_time_sec(timeSec);
}

void StatsdStats::noteDataDropped(const ConfigKey& key) {
    noteDataDropped(key, getWallClockSec());
}

void StatsdStats::noteDataDropped(const ConfigKey& key, int32_t timeSec) {
    lock_guard<std::mutex> lock(mLock);
    auto it = mConfigStats.find(key);
    if (it == mConfigStats.end()) {
        ALOGE("Config key %s not found!", key.ToString().c_str());
        return;
    }
    if (it->second.data_drop_time_sec_size() >= kMaxTimestampCount) {
        auto timestampList = it->second.mutable_data_drop_time_sec();
        // This is O(N) operation. It shouldn't happen often, and N is only 20.
        timestampList->erase(timestampList->begin());
    }
    it->second.add_data_drop_time_sec(timeSec);
}

void StatsdStats::noteMetricsReportSent(const ConfigKey& key) {
    noteMetricsReportSent(key, getWallClockSec());
}

void StatsdStats::noteMetricsReportSent(const ConfigKey& key, int32_t timeSec) {
    lock_guard<std::mutex> lock(mLock);
    auto it = mConfigStats.find(key);
    if (it == mConfigStats.end()) {
        ALOGE("Config key %s not found!", key.ToString().c_str());
        return;
    }
    if (it->second.dump_report_time_sec_size() >= kMaxTimestampCount) {
        auto timestampList = it->second.mutable_dump_report_time_sec();
        // This is O(N) operation. It shouldn't happen often, and N is only 20.
        timestampList->erase(timestampList->begin());
    }
    it->second.add_dump_report_time_sec(timeSec);
}

void StatsdStats::noteUidMapDropped(int snapshots, int deltas) {
    lock_guard<std::mutex> lock(mLock);
    mUidMapStats.set_dropped_snapshots(mUidMapStats.dropped_snapshots() + snapshots);
    mUidMapStats.set_dropped_changes(mUidMapStats.dropped_changes() + deltas);
}

void StatsdStats::setUidMapSnapshots(int snapshots) {
    lock_guard<std::mutex> lock(mLock);
    mUidMapStats.set_snapshots(snapshots);
}

void StatsdStats::setUidMapChanges(int changes) {
    lock_guard<std::mutex> lock(mLock);
    mUidMapStats.set_changes(changes);
}

void StatsdStats::setCurrentUidMapMemory(int bytes) {
    lock_guard<std::mutex> lock(mLock);
    mUidMapStats.set_bytes_used(bytes);
}

void StatsdStats::noteConditionDimensionSize(const ConfigKey& key, const int64_t& id, int size) {
    lock_guard<std::mutex> lock(mLock);
    // if name doesn't exist before, it will create the key with count 0.
    auto& conditionSizeMap = mConditionStats[key];
    if (size > conditionSizeMap[id]) {
        conditionSizeMap[id] = size;
    }
}

void StatsdStats::noteMetricDimensionSize(const ConfigKey& key, const int64_t& id, int size) {
    lock_guard<std::mutex> lock(mLock);
    // if name doesn't exist before, it will create the key with count 0.
    auto& metricsDimensionMap = mMetricsStats[key];
    if (size > metricsDimensionMap[id]) {
        metricsDimensionMap[id] = size;
    }
}

void StatsdStats::noteMatcherMatched(const ConfigKey& key, const int64_t& id) {
    lock_guard<std::mutex> lock(mLock);
    auto& matcherStats = mMatcherStats[key];
    matcherStats[id]++;
}

void StatsdStats::noteAnomalyDeclared(const ConfigKey& key, const int64_t& id) {
    lock_guard<std::mutex> lock(mLock);
    auto& alertStats = mAlertStats[key];
    alertStats[id]++;
}

void StatsdStats::noteRegisteredAnomalyAlarmChanged() {
    lock_guard<std::mutex> lock(mLock);
    mAnomalyAlarmRegisteredStats++;
}

void StatsdStats::noteRegisteredPeriodicAlarmChanged() {
    lock_guard<std::mutex> lock(mLock);
    mPeriodicAlarmRegisteredStats++;
}

void StatsdStats::updateMinPullIntervalSec(int pullAtomId, long intervalSec) {
    lock_guard<std::mutex> lock(mLock);
    mPulledAtomStats[pullAtomId].minPullIntervalSec = intervalSec;
}

void StatsdStats::notePull(int pullAtomId) {
    lock_guard<std::mutex> lock(mLock);
    mPulledAtomStats[pullAtomId].totalPull++;
}

void StatsdStats::notePullFromCache(int pullAtomId) {
    lock_guard<std::mutex> lock(mLock);
    mPulledAtomStats[pullAtomId].totalPullFromCache++;
}

void StatsdStats::noteAtomLogged(int atomId, int32_t timeSec) {
    lock_guard<std::mutex> lock(mLock);

    if (atomId > android::util::kMaxPushedAtomId) {
        ALOGW("not interested in atom %d", atomId);
        return;
    }

    mPushedAtomStats[atomId]++;
}

void StatsdStats::noteLoggerError(int error) {
    lock_guard<std::mutex> lock(mLock);
    // grows strictly one at a time. so it won't > kMaxLoggerErrors
    if (mLoggerErrors.size() == kMaxLoggerErrors) {
        mLoggerErrors.pop_front();
    }
    mLoggerErrors.push_back(std::make_pair(getWallClockSec(), error));
}

void StatsdStats::reset() {
    lock_guard<std::mutex> lock(mLock);
    resetInternalLocked();
}

void StatsdStats::resetInternalLocked() {
    // Reset the historical data, but keep the active ConfigStats
    mStartTimeSec = getWallClockSec();
    mIceBox.clear();
    mConditionStats.clear();
    mMetricsStats.clear();
    std::fill(mPushedAtomStats.begin(), mPushedAtomStats.end(), 0);
    mAlertStats.clear();
    mAnomalyAlarmRegisteredStats = 0;
    mPeriodicAlarmRegisteredStats = 0;
    mMatcherStats.clear();
    mLoggerErrors.clear();
    for (auto& config : mConfigStats) {
        config.second.clear_broadcast_sent_time_sec();
        config.second.clear_data_drop_time_sec();
        config.second.clear_dump_report_time_sec();
        config.second.clear_matcher_stats();
        config.second.clear_condition_stats();
        config.second.clear_metric_stats();
        config.second.clear_alert_stats();
    }
}

void StatsdStats::addSubStatsToConfigLocked(const ConfigKey& key,
                                      StatsdStatsReport_ConfigStats& configStats) {
    // Add matcher stats
    if (mMatcherStats.find(key) != mMatcherStats.end()) {
        const auto& matcherStats = mMatcherStats[key];
        for (const auto& stats : matcherStats) {
            auto output = configStats.add_matcher_stats();
            output->set_id(stats.first);
            output->set_matched_times(stats.second);
            VLOG("matcher %lld matched %d times",
                (long long)stats.first, stats.second);
        }
    }
    // Add condition stats
    if (mConditionStats.find(key) != mConditionStats.end()) {
        const auto& conditionStats = mConditionStats[key];
        for (const auto& stats : conditionStats) {
            auto output = configStats.add_condition_stats();
            output->set_id(stats.first);
            output->set_max_tuple_counts(stats.second);
            VLOG("condition %lld max output tuple size %d",
                (long long)stats.first, stats.second);
        }
    }
    // Add metrics stats
    if (mMetricsStats.find(key) != mMetricsStats.end()) {
        const auto& conditionStats = mMetricsStats[key];
        for (const auto& stats : conditionStats) {
            auto output = configStats.add_metric_stats();
            output->set_id(stats.first);
            output->set_max_tuple_counts(stats.second);
            VLOG("metrics %lld max output tuple size %d",
                (long long)stats.first, stats.second);
        }
    }
    // Add anomaly detection alert stats
    if (mAlertStats.find(key) != mAlertStats.end()) {
        const auto& alertStats = mAlertStats[key];
        for (const auto& stats : alertStats) {
            auto output = configStats.add_alert_stats();
            output->set_id(stats.first);
            output->set_alerted_times(stats.second);
            VLOG("alert %lld declared %d times", (long long)stats.first, stats.second);
        }
    }
}

void StatsdStats::dumpStats(FILE* out) const {
    lock_guard<std::mutex> lock(mLock);
    time_t t = mStartTimeSec;
    struct tm* tm = localtime(&t);
    char timeBuffer[80];
    strftime(timeBuffer, sizeof(timeBuffer), "%Y-%m-%d %I:%M%p\n", tm);
    fprintf(out, "Stats collection start second: %s\n", timeBuffer);
    fprintf(out, "%lu Config in icebox: \n", (unsigned long)mIceBox.size());
    for (const auto& configStats : mIceBox) {
        fprintf(out,
                "Config {%d_%lld}: creation=%d, deletion=%d, #metric=%d, #condition=%d, "
                "#matcher=%d, #alert=%d,  valid=%d\n",
                configStats.uid(), (long long)configStats.id(), configStats.creation_time_sec(),
                configStats.deletion_time_sec(), configStats.metric_count(),
                configStats.condition_count(), configStats.matcher_count(),
                configStats.alert_count(), configStats.is_valid());

        for (const auto& broadcastTime : configStats.broadcast_sent_time_sec()) {
            fprintf(out, "\tbroadcast time: %d\n", broadcastTime);
        }

        for (const auto& dataDropTime : configStats.data_drop_time_sec()) {
            fprintf(out, "\tdata drop time: %d\n", dataDropTime);
        }
    }
    fprintf(out, "%lu Active Configs\n", (unsigned long)mConfigStats.size());
    for (auto& pair : mConfigStats) {
        auto& key = pair.first;
        auto& configStats = pair.second;

        fprintf(out,
                "Config {%d-%lld}: creation=%d, deletion=%d, #metric=%d, #condition=%d, "
                "#matcher=%d, #alert=%d,  valid=%d\n",
                configStats.uid(), (long long)configStats.id(), configStats.creation_time_sec(),
                configStats.deletion_time_sec(), configStats.metric_count(),
                configStats.condition_count(), configStats.matcher_count(),
                configStats.alert_count(), configStats.is_valid());
        for (const auto& broadcastTime : configStats.broadcast_sent_time_sec()) {
            fprintf(out, "\tbroadcast time: %d\n", broadcastTime);
        }

        for (const auto& dataDropTime : configStats.data_drop_time_sec()) {
            fprintf(out, "\tdata drop time: %d\n", dataDropTime);
        }

        for (const auto& dumpTime : configStats.dump_report_time_sec()) {
            fprintf(out, "\tdump report time: %d\n", dumpTime);
        }

        // Add matcher stats
        auto matcherIt = mMatcherStats.find(key);
        if (matcherIt != mMatcherStats.end()) {
            const auto& matcherStats = matcherIt->second;
            for (const auto& stats : matcherStats) {
                fprintf(out, "matcher %lld matched %d times\n", (long long)stats.first,
                        stats.second);
            }
        }
        // Add condition stats
        auto conditionIt = mConditionStats.find(key);
        if (conditionIt != mConditionStats.end()) {
            const auto& conditionStats = conditionIt->second;
            for (const auto& stats : conditionStats) {
                fprintf(out, "condition %lld max output tuple size %d\n", (long long)stats.first,
                        stats.second);
            }
        }
        // Add metrics stats
        auto metricIt = mMetricsStats.find(key);
        if (metricIt != mMetricsStats.end()) {
            const auto& conditionStats = metricIt->second;
            for (const auto& stats : conditionStats) {
                fprintf(out, "metrics %lld max output tuple size %d\n", (long long)stats.first,
                        stats.second);
            }
        }
        // Add anomaly detection alert stats
        auto alertIt = mAlertStats.find(key);
        if (alertIt != mAlertStats.end()) {
            const auto& alertStats = alertIt->second;
            for (const auto& stats : alertStats) {
                fprintf(out, "alert %lld declared %d times\n", (long long)stats.first,
                        stats.second);
            }
        }
    }
    fprintf(out, "********Pushed Atom stats***********\n");
    const size_t atomCounts = mPushedAtomStats.size();
    for (size_t i = 2; i < atomCounts; i++) {
        if (mPushedAtomStats[i] > 0) {
            fprintf(out, "Atom %lu->%d\n", (unsigned long)i, mPushedAtomStats[i]);
        }
    }

    fprintf(out, "********Pulled Atom stats***********\n");
    for (const auto& pair : mPulledAtomStats) {
        fprintf(out, "Atom %d->%ld, %ld, %ld\n", (int)pair.first, (long)pair.second.totalPull,
             (long)pair.second.totalPullFromCache, (long)pair.second.minPullIntervalSec);
    }

    if (mAnomalyAlarmRegisteredStats > 0) {
        fprintf(out, "********AnomalyAlarmStats stats***********\n");
        fprintf(out, "Anomaly alarm registrations: %d\n", mAnomalyAlarmRegisteredStats);
    }

    if (mPeriodicAlarmRegisteredStats > 0) {
        fprintf(out, "********SubscriberAlarmStats stats***********\n");
        fprintf(out, "Subscriber alarm registrations: %d\n", mPeriodicAlarmRegisteredStats);
    }

    fprintf(out,
            "UID map stats: bytes=%d, snapshots=%d, changes=%d, snapshots lost=%d, changes "
            "lost=%d\n",
            mUidMapStats.bytes_used(), mUidMapStats.snapshots(), mUidMapStats.changes(),
            mUidMapStats.dropped_snapshots(), mUidMapStats.dropped_changes());

    for (const auto& error : mLoggerErrors) {
        time_t error_time = error.first;
        struct tm* error_tm = localtime(&error_time);
        char buffer[80];
        strftime(buffer, sizeof(buffer), "%Y-%m-%d %I:%M%p\n", error_tm);
        fprintf(out, "Logger error %d at %s\n", error.second, buffer);
    }
}

void StatsdStats::dumpStats(std::vector<uint8_t>* output, bool reset) {
    lock_guard<std::mutex> lock(mLock);

    ProtoOutputStream proto;
    proto.write(FIELD_TYPE_INT32 | FIELD_ID_BEGIN_TIME, mStartTimeSec);
    proto.write(FIELD_TYPE_INT32 | FIELD_ID_END_TIME, (int32_t)getWallClockSec());

    for (const auto& configStats : mIceBox) {
        const int numBytes = configStats.ByteSize();
        vector<char> buffer(numBytes);
        configStats.SerializeToArray(&buffer[0], numBytes);
        proto.write(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_CONFIG_STATS, &buffer[0],
                    buffer.size());
    }

    for (auto& pair : mConfigStats) {
        auto& configStats = pair.second;
        addSubStatsToConfigLocked(pair.first, configStats);

        const int numBytes = configStats.ByteSize();
        vector<char> buffer(numBytes);
        configStats.SerializeToArray(&buffer[0], numBytes);
        proto.write(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_CONFIG_STATS, &buffer[0],
                    buffer.size());
        // reset the sub stats, the source of truth is in the individual map
        // they will be repopulated when dumpStats() is called again.
        configStats.clear_matcher_stats();
        configStats.clear_condition_stats();
        configStats.clear_metric_stats();
        configStats.clear_alert_stats();
    }

    const size_t atomCounts = mPushedAtomStats.size();
    for (size_t i = 2; i < atomCounts; i++) {
        if (mPushedAtomStats[i] > 0) {
            long long token =
                    proto.start(FIELD_TYPE_MESSAGE | FIELD_ID_ATOM_STATS | FIELD_COUNT_REPEATED);
            proto.write(FIELD_TYPE_INT32 | FIELD_ID_ATOM_STATS_TAG, (int32_t)i);
            proto.write(FIELD_TYPE_INT32 | FIELD_ID_ATOM_STATS_COUNT, mPushedAtomStats[i]);
            proto.end(token);
        }
    }

    for (const auto& pair : mPulledAtomStats) {
        android::os::statsd::writePullerStatsToStream(pair, &proto);
    }

    if (mAnomalyAlarmRegisteredStats > 0) {
        long long token = proto.start(FIELD_TYPE_MESSAGE | FIELD_ID_ANOMALY_ALARM_STATS);
        proto.write(FIELD_TYPE_INT32 | FIELD_ID_ANOMALY_ALARMS_REGISTERED,
                    mAnomalyAlarmRegisteredStats);
        proto.end(token);
    }

    if (mPeriodicAlarmRegisteredStats > 0) {
        long long token = proto.start(FIELD_TYPE_MESSAGE | FIELD_ID_SUBSCRIBER_ALARM_STATS);
        proto.write(FIELD_TYPE_INT32 | FIELD_ID_SUBSCRIBER_ALARMS_REGISTERED,
                    mPeriodicAlarmRegisteredStats);
        proto.end(token);
    }

    const int numBytes = mUidMapStats.ByteSize();
    vector<char> buffer(numBytes);
    mUidMapStats.SerializeToArray(&buffer[0], numBytes);
    proto.write(FIELD_TYPE_MESSAGE | FIELD_ID_UIDMAP_STATS, &buffer[0], buffer.size());

    for (const auto& error : mLoggerErrors) {
        long long token = proto.start(FIELD_TYPE_MESSAGE | FIELD_ID_LOGGER_ERROR_STATS |
                                      FIELD_COUNT_REPEATED);
        proto.write(FIELD_TYPE_INT32 | FIELD_ID_LOGGER_STATS_TIME, error.first);
        proto.write(FIELD_TYPE_INT32 | FIELD_ID_LOGGER_STATS_ERROR_CODE, error.second);
        proto.end(token);
    }

    output->clear();
    size_t bufferSize = proto.size();
    output->resize(bufferSize);

    size_t pos = 0;
    auto it = proto.data();
    while (it.readBuffer() != NULL) {
        size_t toRead = it.currentToRead();
        std::memcpy(&((*output)[pos]), it.readBuffer(), toRead);
        pos += toRead;
        it.rp()->move(toRead);
    }

    if (reset) {
        resetInternalLocked();
    }

    VLOG("reset=%d, returned proto size %lu", reset, (unsigned long)bufferSize);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
