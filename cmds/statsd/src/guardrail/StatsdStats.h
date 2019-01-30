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
#pragma once

#include "config/ConfigKey.h"
#include "statslog.h"

#include <gtest/gtest_prod.h>
#include <log/log_time.h>
#include <list>
#include <mutex>
#include <string>
#include <vector>

namespace android {
namespace os {
namespace statsd {

struct ConfigStats {
    int32_t uid;
    int64_t id;
    int32_t creation_time_sec;
    int32_t deletion_time_sec = 0;
    int32_t reset_time_sec = 0;
    int32_t metric_count;
    int32_t condition_count;
    int32_t matcher_count;
    int32_t alert_count;
    bool is_valid;

    std::list<int32_t> broadcast_sent_time_sec;
    std::list<int32_t> data_drop_time_sec;
    // Number of bytes dropped at corresponding time.
    std::list<int64_t> data_drop_bytes;
    std::list<std::pair<int32_t, int64_t>> dump_report_stats;

    // Stores how many times a matcher have been matched. The map size is capped by kMaxConfigCount.
    std::map<const int64_t, int> matcher_stats;

    // Stores the number of output tuple of condition trackers when it's bigger than
    // kDimensionKeySizeSoftLimit. When you see the number is kDimensionKeySizeHardLimit +1,
    // it means some data has been dropped. The map size is capped by kMaxConfigCount.
    std::map<const int64_t, int> condition_stats;

    // Stores the number of output tuple of metric producers when it's bigger than
    // kDimensionKeySizeSoftLimit. When you see the number is kDimensionKeySizeHardLimit +1,
    // it means some data has been dropped. The map size is capped by kMaxConfigCount.
    std::map<const int64_t, int> metric_stats;

    // Stores the max number of output tuple of dimensions in condition across dimensions in what
    // when it's bigger than kDimensionKeySizeSoftLimit. When you see the number is
    // kDimensionKeySizeHardLimit +1, it means some data has been dropped. The map size is capped by
    // kMaxConfigCount.
    std::map<const int64_t, int> metric_dimension_in_condition_stats;

    // Stores the number of times an anomaly detection alert has been declared.
    // The map size is capped by kMaxConfigCount.
    std::map<const int64_t, int> alert_stats;

    // Stores the config ID for each sub-config used.
    std::list<std::pair<const int64_t, const int32_t>> annotations;
};

struct UidMapStats {
    int32_t changes;
    int32_t bytes_used;
    int32_t dropped_changes;
    int32_t deleted_apps = 0;
};

// Keeps track of stats of statsd.
// Single instance shared across the process. All public methods are thread safe.
class StatsdStats {
public:
    static StatsdStats& getInstance();
    ~StatsdStats(){};

    const static int kDimensionKeySizeSoftLimit = 500;
    const static int kDimensionKeySizeHardLimit = 800;

    // Per atom dimension key size limit
    static const std::map<int, std::pair<size_t, size_t>> kAtomDimensionKeySizeLimitMap;

    const static int kMaxConfigCountPerUid = 10;
    const static int kMaxAlertCountPerConfig = 100;
    const static int kMaxConditionCountPerConfig = 300;
    const static int kMaxMetricCountPerConfig = 1000;
    const static int kMaxMatcherCountPerConfig = 800;

    // The max number of old config stats we keep.
    const static int kMaxIceBoxSize = 20;

    const static int kMaxLoggerErrors = 20;

    const static int kMaxSystemServerRestarts = 20;

    const static int kMaxTimestampCount = 20;

    const static int kMaxLogSourceCount = 50;

    // Max memory allowed for storing metrics per configuration. If this limit is exceeded, statsd
    // drops the metrics data in memory.
    static const size_t kMaxMetricsBytesPerConfig = 2 * 1024 * 1024;

    // Soft memory limit per configuration. Once this limit is exceeded, we begin notifying the
    // data subscriber that it's time to call getData.
    static const size_t kBytesPerConfigTriggerGetData = 192 * 1024;

    // Cap the UID map's memory usage to this. This should be fairly high since the UID information
    // is critical for understanding the metrics.
    const static size_t kMaxBytesUsedUidMap = 50 * 1024;

    // The number of deleted apps that are stored in the uid map.
    const static int kMaxDeletedAppsInUidMap = 100;

    /* Minimum period between two broadcasts in nanoseconds. */
    static const int64_t kMinBroadcastPeriodNs = 60 * NS_PER_SEC;

    /* Min period between two checks of byte size per config key in nanoseconds. */
    static const int64_t kMinByteSizeCheckPeriodNs = 60 * NS_PER_SEC;

    // Maximum age (30 days) that files on disk can exist in seconds.
    static const int kMaxAgeSecond = 60 * 60 * 24 * 30;

    // Maximum number of files (1000) that can be in stats directory on disk.
    static const int kMaxFileNumber = 1000;

    // Maximum size of all files that can be written to stats directory on disk.
    static const int kMaxFileSize = 50 * 1024 * 1024;

    // How long to try to clear puller cache from last time
    static const long kPullerCacheClearIntervalSec = 1;

    // Max time to do a pull.
    static const int64_t kPullMaxDelayNs = 10 * NS_PER_SEC;
    /**
     * Report a new config has been received and report the static stats about the config.
     *
     * The static stats include: the count of metrics, conditions, matchers, and alerts.
     * If the config is not valid, this config stats will be put into icebox immediately.
     */
    void noteConfigReceived(const ConfigKey& key, int metricsCount, int conditionsCount,
                            int matchersCount, int alertCount,
                            const std::list<std::pair<const int64_t, const int32_t>>& annotations,
                            bool isValid);
    /**
     * Report a config has been removed.
     */
    void noteConfigRemoved(const ConfigKey& key);
    /**
     * Report a config has been reset when ttl expires.
     */
    void noteConfigReset(const ConfigKey& key);

    /**
     * Report a broadcast has been sent to a config owner to collect the data.
     */
    void noteBroadcastSent(const ConfigKey& key);

    /**
     * Report a config's metrics data has been dropped.
     */
    void noteDataDropped(const ConfigKey& key, const size_t totalBytes);

    /**
     * Report metrics data report has been sent.
     *
     * The report may be requested via StatsManager API, or through adb cmd.
     */
    void noteMetricsReportSent(const ConfigKey& key, const size_t num_bytes);

    /**
     * Report the size of output tuple of a condition.
     *
     * Note: only report when the condition has an output dimension, and the tuple
     * count > kDimensionKeySizeSoftLimit.
     *
     * [key]: The config key that this condition belongs to.
     * [id]: The id of the condition.
     * [size]: The output tuple size.
     */
    void noteConditionDimensionSize(const ConfigKey& key, const int64_t& id, int size);

    /**
     * Report the size of output tuple of a metric.
     *
     * Note: only report when the metric has an output dimension, and the tuple
     * count > kDimensionKeySizeSoftLimit.
     *
     * [key]: The config key that this metric belongs to.
     * [id]: The id of the metric.
     * [size]: The output tuple size.
     */
    void noteMetricDimensionSize(const ConfigKey& key, const int64_t& id, int size);

    /**
     * Report the max size of output tuple of dimension in condition across dimensions in what.
     *
     * Note: only report when the metric has an output dimension in condition, and the max tuple
     * count > kDimensionKeySizeSoftLimit.
     *
     * [key]: The config key that this metric belongs to.
     * [id]: The id of the metric.
     * [size]: The output tuple size.
     */
    void noteMetricDimensionInConditionSize(const ConfigKey& key, const int64_t& id, int size);

    /**
     * Report a matcher has been matched.
     *
     * [key]: The config key that this matcher belongs to.
     * [id]: The id of the matcher.
     */
    void noteMatcherMatched(const ConfigKey& key, const int64_t& id);

    /**
     * Report that an anomaly detection alert has been declared.
     *
     * [key]: The config key that this alert belongs to.
     * [id]: The id of the alert.
     */
    void noteAnomalyDeclared(const ConfigKey& key, const int64_t& id);

    /**
     * Report an atom event has been logged.
     */
    void noteAtomLogged(int atomId, int32_t timeSec);

    /**
     * Report that statsd modified the anomaly alarm registered with StatsCompanionService.
     */
    void noteRegisteredAnomalyAlarmChanged();

    /**
     * Report that statsd modified the periodic alarm registered with StatsCompanionService.
     */
    void noteRegisteredPeriodicAlarmChanged();

    /**
     * Records the number of delta entries that are being dropped from the uid map.
     */
    void noteUidMapDropped(int deltas);

    /**
     * Records that an app was deleted (from statsd's map).
     */
    void noteUidMapAppDeletionDropped();

    /**
     * Updates the number of changes currently stored in the uid map.
     */
    void setUidMapChanges(int changes);
    void setCurrentUidMapMemory(int bytes);

    /*
     * Updates minimum interval between pulls for an pulled atom.
     */
    void updateMinPullIntervalSec(int pullAtomId, long intervalSec);

    /*
     * Notes an atom is pulled.
     */
    void notePull(int pullAtomId);

    /*
     * Notes an atom is served from puller cache.
     */
    void notePullFromCache(int pullAtomId);

    /*
     * Notify data error for pulled atom.
     */
    void notePullDataError(int pullAtomId);

    /*
     * Records time for actual pulling, not including those served from cache and not including
     * statsd processing delays.
     */
    void notePullTime(int pullAtomId, int64_t pullTimeNs);

    /*
     * Records pull delay for a pulled atom, including those served from cache and including statsd
     * processing delays.
     */
    void notePullDelay(int pullAtomId, int64_t pullDelayNs);

    /*
     * Records pull exceeds timeout for the puller.
     */
    void notePullTimeout(int pullAtomId);

    /*
     * Records pull exceeds max delay for a metric.
     */
    void notePullExceedMaxDelay(int pullAtomId);

    /*
     * Records when system server restarts.
     */
    void noteSystemServerRestart(int32_t timeSec);

    /**
     * Records statsd skipped an event.
     */
    void noteLogLost(int32_t wallClockTimeSec, int32_t count, int lastError);

    /**
     * Records that the pull of an atom has failed
     */
    void notePullFailed(int atomId);

    /**
     * Records that the pull of StatsCompanionService atom has failed
     */
    void noteStatsCompanionPullFailed(int atomId);

    /**
     * Records that the pull of a StatsCompanionService atom has failed due to a failed binder
     * transaction. This can happen when StatsCompanionService returns too
     * much data (the max Binder parcel size is 1MB)
     */
    void noteStatsCompanionPullBinderTransactionFailed(int atomId);

    /**
     * A pull with no data occurred
     */
    void noteEmptyData(int atomId);

    /**
     * Hard limit was reached in the cardinality of an atom
     */
    void noteHardDimensionLimitReached(int atomId);

    /**
     * A log event was too late, arrived in the wrong bucket and was skipped
     */
    void noteLateLogEventSkipped(int atomId);

    /**
     * Buckets were skipped as time elapsed without any data for them
     */
    void noteSkippedForwardBuckets(int atomId);

    /**
     * An unsupported value type was received
     */
    void noteBadValueType(int atomId);

    /**
     * A condition change was too late, arrived in the wrong bucket and was skipped
     */
    void noteConditionChangeInNextBucket(int atomId);

    /**
     * Reset the historical stats. Including all stats in icebox, and the tracked stats about
     * metrics, matchers, and atoms. The active configs will be kept and StatsdStats will continue
     * to collect stats after reset() has been called.
     */
    void reset();

    /**
     * Output the stats in protobuf binary format to [buffer].
     *
     * [reset]: whether to clear the historical stats after the call.
     */
    void dumpStats(std::vector<uint8_t>* buffer, bool reset);

    /**
     * Output statsd stats in human readable format to [out] file descriptor.
     */
    void dumpStats(int outFd) const;

    typedef struct {
        long totalPull = 0;
        long totalPullFromCache = 0;
        long minPullIntervalSec = LONG_MAX;
        int64_t avgPullTimeNs = 0;
        int64_t maxPullTimeNs = 0;
        long numPullTime = 0;
        int64_t avgPullDelayNs = 0;
        int64_t maxPullDelayNs = 0;
        long numPullDelay = 0;
        long dataError = 0;
        long pullTimeout = 0;
        long pullExceedMaxDelay = 0;
        long pullFailed = 0;
        long statsCompanionPullFailed = 0;
        long statsCompanionPullBinderTransactionFailed = 0;
        long emptyData = 0;
    } PulledAtomStats;

    typedef struct {
        long hardDimensionLimitReached = 0;
        long lateLogEventSkipped = 0;
        long skippedForwardBuckets = 0;
        long badValueType = 0;
        long conditionChangeInNextBucket = 0;
    } AtomMetricStats;

private:
    StatsdStats();

    mutable std::mutex mLock;

    int32_t mStartTimeSec;

    // Track the number of dropped entries used by the uid map.
    UidMapStats mUidMapStats;

    // The stats about the configs that are still in use.
    // The map size is capped by kMaxConfigCount.
    std::map<const ConfigKey, std::shared_ptr<ConfigStats>> mConfigStats;

    // Stores the stats for the configs that are no longer in use.
    // The size of the vector is capped by kMaxIceBoxSize.
    std::list<const std::shared_ptr<ConfigStats>> mIceBox;

    // Stores the number of times a pushed atom is logged.
    // The size of the vector is the largest pushed atom id in atoms.proto + 1. Atoms
    // out of that range will be dropped (it's either pulled atoms or test atoms).
    // This is a vector, not a map because it will be accessed A LOT -- for each stats log.
    std::vector<int> mPushedAtomStats;

    // Maps PullAtomId to its stats. The size is capped by the puller atom counts.
    std::map<int, PulledAtomStats> mPulledAtomStats;

    // Maps metric ID to its stats. The size is capped by the number of metrics.
    std::map<int, AtomMetricStats> mAtomMetricStats;

    struct LogLossStats {
        LogLossStats(int32_t sec, int32_t count, int32_t error)
            : mWallClockSec(sec), mCount(count), mLastError(error) {
        }
        int32_t mWallClockSec;
        int32_t mCount;
        // error code defined in linux/errno.h
        int32_t mLastError;
    };

    // Timestamps when we detect log loss, and the number of logs lost.
    std::list<LogLossStats> mLogLossStats;

    std::list<int32_t> mSystemServerRestartSec;

    // Stores the number of times statsd modified the anomaly alarm registered with
    // StatsCompanionService.
    int mAnomalyAlarmRegisteredStats = 0;

    // Stores the number of times statsd registers the periodic alarm changes
    int mPeriodicAlarmRegisteredStats = 0;

    void noteConfigResetInternalLocked(const ConfigKey& key);

    void noteConfigRemovedInternalLocked(const ConfigKey& key);

    void resetInternalLocked();

    void noteDataDropped(const ConfigKey& key, const size_t totalBytes, int32_t timeSec);

    void noteMetricsReportSent(const ConfigKey& key, const size_t num_bytes, int32_t timeSec);

    void noteBroadcastSent(const ConfigKey& key, int32_t timeSec);

    void addToIceBoxLocked(std::shared_ptr<ConfigStats>& stats);

    /**
     * Get a reference to AtomMetricStats for a metric. If none exists, create it. The reference
     * will live as long as `this`.
     */
    StatsdStats::AtomMetricStats& getAtomMetricStats(int metricId);

    FRIEND_TEST(StatsdStatsTest, TestValidConfigAdd);
    FRIEND_TEST(StatsdStatsTest, TestInvalidConfigAdd);
    FRIEND_TEST(StatsdStatsTest, TestConfigRemove);
    FRIEND_TEST(StatsdStatsTest, TestSubStats);
    FRIEND_TEST(StatsdStatsTest, TestAtomLog);
    FRIEND_TEST(StatsdStatsTest, TestTimestampThreshold);
    FRIEND_TEST(StatsdStatsTest, TestAnomalyMonitor);
    FRIEND_TEST(StatsdStatsTest, TestSystemServerCrash);
    FRIEND_TEST(StatsdStatsTest, TestPullAtomStats);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
