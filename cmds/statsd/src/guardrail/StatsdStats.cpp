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
#define DEBUG true  // STOPSHIP if true
#include "Log.h"

#include "StatsdStats.h"

#include <android/util/ProtoOutputStream.h>
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
const int FIELD_ID_MATCHER_STATS = 4;
const int FIELD_ID_CONDITION_STATS = 5;
const int FIELD_ID_METRIC_STATS = 6;
const int FIELD_ID_ATOM_STATS = 7;

const int FIELD_ID_MATCHER_STATS_NAME = 1;
const int FIELD_ID_MATCHER_STATS_COUNT = 2;

const int FIELD_ID_CONDITION_STATS_NAME = 1;
const int FIELD_ID_CONDITION_STATS_COUNT = 2;

const int FIELD_ID_METRIC_STATS_NAME = 1;
const int FIELD_ID_METRIC_STATS_COUNT = 2;

const int FIELD_ID_ATOM_STATS_TAG = 1;
const int FIELD_ID_ATOM_STATS_COUNT = 2;

// TODO: add stats for pulled atoms.
StatsdStats::StatsdStats() {
    mPushedAtomStats.resize(android::util::kMaxPushedAtomId + 1);
    mStartTimeSec = time(nullptr);
}

StatsdStats& StatsdStats::getInstance() {
    static StatsdStats statsInstance;
    return statsInstance;
}

void StatsdStats::noteConfigReceived(const ConfigKey& key, int metricsCount, int conditionsCount,
                                     int matchersCount, int alertsCount, bool isValid) {
    lock_guard<std::mutex> lock(mLock);
    int32_t nowTimeSec = time(nullptr);

    // If there is an existing config for the same key, icebox the old config.
    noteConfigRemovedInternalLocked(key);

    StatsdStatsReport_ConfigStats configStats;
    configStats.set_uid(key.GetUid());
    configStats.set_name(key.GetName());
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
        mIceBox.push_back(configStats);
    }
}

void StatsdStats::noteConfigRemovedInternalLocked(const ConfigKey& key) {
    auto it = mConfigStats.find(key);
    if (it != mConfigStats.end()) {
        int32_t nowTimeSec = time(nullptr);
        it->second.set_deletion_time_sec(nowTimeSec);
        // Add condition stats, metrics stats, matcher stats
        addSubStatsToConfig(key, it->second);
        // Remove them after they are added to the config stats.
        mMatcherStats.erase(key);
        mMetricsStats.erase(key);
        mConditionStats.erase(key);
        mIceBox.push_back(it->second);
        mConfigStats.erase(it);
    }
}

void StatsdStats::noteConfigRemoved(const ConfigKey& key) {
    lock_guard<std::mutex> lock(mLock);
    noteConfigRemovedInternalLocked(key);
}

void StatsdStats::noteBroadcastSent(const ConfigKey& key) {
    noteBroadcastSent(key, time(nullptr));
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
    noteDataDropped(key, time(nullptr));
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
    noteMetricsReportSent(key, time(nullptr));
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

void StatsdStats::noteConditionDimensionSize(const ConfigKey& key, const string& name, int size) {
    lock_guard<std::mutex> lock(mLock);
    // if name doesn't exist before, it will create the key with count 0.
    auto& conditionSizeMap = mConditionStats[key];
    if (size > conditionSizeMap[name]) {
        conditionSizeMap[name] = size;
    }
}

void StatsdStats::noteMetricDimensionSize(const ConfigKey& key, const string& name, int size) {
    lock_guard<std::mutex> lock(mLock);
    // if name doesn't exist before, it will create the key with count 0.
    auto& metricsDimensionMap = mMetricsStats[key];
    if (size > metricsDimensionMap[name]) {
        metricsDimensionMap[name] = size;
    }
}

void StatsdStats::noteMatcherMatched(const ConfigKey& key, const string& name) {
    lock_guard<std::mutex> lock(mLock);
    auto& matcherStats = mMatcherStats[key];
    matcherStats[name]++;
}

void StatsdStats::noteAtomLogged(int atomId, int32_t timeSec) {
    lock_guard<std::mutex> lock(mLock);

    if (timeSec < mStartTimeSec) {
        return;
    }

    if (atomId > android::util::kMaxPushedAtomId) {
        ALOGW("not interested in atom %d", atomId);
        return;
    }

    mPushedAtomStats[atomId]++;
}

void StatsdStats::reset() {
    lock_guard<std::mutex> lock(mLock);
    resetInternalLocked();
}

void StatsdStats::resetInternalLocked() {
    // Reset the historical data, but keep the active ConfigStats
    mStartTimeSec = time(nullptr);
    mIceBox.clear();
    mConditionStats.clear();
    mMetricsStats.clear();
    std::fill(mPushedAtomStats.begin(), mPushedAtomStats.end(), 0);
    mMatcherStats.clear();
    for (auto& config : mConfigStats) {
        config.second.clear_broadcast_sent_time_sec();
        config.second.clear_data_drop_time_sec();
        config.second.clear_dump_report_time_sec();
        config.second.clear_matcher_stats();
        config.second.clear_condition_stats();
        config.second.clear_metric_stats();
    }
}

void StatsdStats::addSubStatsToConfig(const ConfigKey& key,
                                      StatsdStatsReport_ConfigStats& configStats) {
    // Add matcher stats
    if (mMatcherStats.find(key) != mMatcherStats.end()) {
        const auto& matcherStats = mMatcherStats[key];
        for (const auto& stats : matcherStats) {
            auto output = configStats.add_matcher_stats();
            output->set_name(stats.first);
            output->set_matched_times(stats.second);
            VLOG("matcher %s matched %d times", stats.first.c_str(), stats.second);
        }
    }
    // Add condition stats
    if (mConditionStats.find(key) != mConditionStats.end()) {
        const auto& conditionStats = mConditionStats[key];
        for (const auto& stats : conditionStats) {
            auto output = configStats.add_condition_stats();
            output->set_name(stats.first);
            output->set_max_tuple_counts(stats.second);
            VLOG("condition %s max output tuple size %d", stats.first.c_str(), stats.second);
        }
    }
    // Add metrics stats
    if (mMetricsStats.find(key) != mMetricsStats.end()) {
        const auto& conditionStats = mMetricsStats[key];
        for (const auto& stats : conditionStats) {
            auto output = configStats.add_metric_stats();
            output->set_name(stats.first);
            output->set_max_tuple_counts(stats.second);
            VLOG("metrics %s max output tuple size %d", stats.first.c_str(), stats.second);
        }
    }
}

void StatsdStats::dumpStats(std::vector<uint8_t>* output, bool reset) {
    lock_guard<std::mutex> lock(mLock);

    if (DEBUG) {
        time_t t = mStartTimeSec;
        struct tm* tm = localtime(&t);
        char timeBuffer[80];
        strftime(timeBuffer, sizeof(timeBuffer), "%Y-%m-%d %I:%M%p", tm);
        VLOG("=================StatsdStats dump begins====================");
        VLOG("Stats collection start second: %s", timeBuffer);
    }
    ProtoOutputStream proto;
    proto.write(FIELD_TYPE_INT32 | FIELD_ID_BEGIN_TIME, mStartTimeSec);
    proto.write(FIELD_TYPE_INT32 | FIELD_ID_END_TIME, (int32_t)time(nullptr));

    VLOG("%lu Config in icebox: ", (unsigned long)mIceBox.size());
    for (const auto& configStats : mIceBox) {
        const int numBytes = configStats.ByteSize();
        vector<char> buffer(numBytes);
        configStats.SerializeToArray(&buffer[0], numBytes);
        proto.write(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | FIELD_ID_CONFIG_STATS, &buffer[0],
                    buffer.size());

        // surround the whole block with DEBUG, so that compiler can strip out the code
        // in production.
        if (DEBUG) {
            VLOG("*****ICEBOX*****");
            VLOG("Config {%d-%s}: creation=%d, deletion=%d, #metric=%d, #condition=%d, "
                 "#matcher=%d, #alert=%d,  #valid=%d",
                 configStats.uid(), configStats.name().c_str(), configStats.creation_time_sec(),
                 configStats.deletion_time_sec(), configStats.metric_count(),
                 configStats.condition_count(), configStats.matcher_count(),
                 configStats.alert_count(), configStats.is_valid());

            for (const auto& broadcastTime : configStats.broadcast_sent_time_sec()) {
                VLOG("\tbroadcast time: %d", broadcastTime);
            }

            for (const auto& dataDropTime : configStats.data_drop_time_sec()) {
                VLOG("\tdata drop time: %d", dataDropTime);
            }
        }
    }

    for (auto& pair : mConfigStats) {
        auto& configStats = pair.second;
        if (DEBUG) {
            VLOG("********Active Configs***********");
            VLOG("Config {%d-%s}: creation=%d, deletion=%d, #metric=%d, #condition=%d, "
                 "#matcher=%d, #alert=%d,  #valid=%d",
                 configStats.uid(), configStats.name().c_str(), configStats.creation_time_sec(),
                 configStats.deletion_time_sec(), configStats.metric_count(),
                 configStats.condition_count(), configStats.matcher_count(),
                 configStats.alert_count(), configStats.is_valid());
            for (const auto& broadcastTime : configStats.broadcast_sent_time_sec()) {
                VLOG("\tbroadcast time: %d", broadcastTime);
            }

            for (const auto& dataDropTime : configStats.data_drop_time_sec()) {
                VLOG("\tdata drop time: %d", dataDropTime);
            }

            for (const auto& dumpTime : configStats.dump_report_time_sec()) {
                VLOG("\tdump report time: %d", dumpTime);
            }
        }

        addSubStatsToConfig(pair.first, configStats);

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
    }

    VLOG("********Atom stats***********");
    const size_t atomCounts = mPushedAtomStats.size();
    for (size_t i = 2; i < atomCounts; i++) {
        if (mPushedAtomStats[i] > 0) {
            long long token =
                    proto.start(FIELD_TYPE_MESSAGE | FIELD_ID_ATOM_STATS | FIELD_COUNT_REPEATED);
            proto.write(FIELD_TYPE_INT32 | FIELD_ID_ATOM_STATS_TAG, (int32_t)i);
            proto.write(FIELD_TYPE_INT32 | FIELD_ID_ATOM_STATS_COUNT, mPushedAtomStats[i]);
            proto.end(token);

            VLOG("Atom %lu->%d\n", (unsigned long)i, mPushedAtomStats[i]);
        }
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
    VLOG("=================StatsdStats dump ends====================");
}

}  // namespace statsd
}  // namespace os
}  // namespace android