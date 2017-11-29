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
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"

#include <gtest/gtest_prod.h>
#include <mutex>
#include <string>
#include <vector>

namespace android {
namespace os {
namespace statsd {

// Keeps track of stats of statsd.
// Single instance shared across the process. All methods are thread safe.
class StatsdStats {
public:
    static StatsdStats& getInstance();
    ~StatsdStats(){};

    // TODO: set different limit if the device is low ram.
    const static int kDimensionKeySizeSoftLimit = 300;
    const static int kDimensionKeySizeHardLimit = 500;

    const static int kMaxConfigCount = 10;
    const static int kMaxConditionCountPerConfig = 200;
    const static int kMaxMetricCountPerConfig = 300;
    const static int kMaxMatcherCountPerConfig = 500;

    /**
     * Report a new config has been received and report the static stats about the config.
     *
     * The static stats include: the count of metrics, conditions, matchers, and alerts.
     * If the config is not valid, this config stats will be put into icebox immediately.
     */
    void noteConfigReceived(const ConfigKey& key, int metricsCount, int conditionsCount,
                            int matchersCount, int alertCount, bool isValid);
    /**
     * Report a config has been removed.
     */
    void noteConfigRemoved(const ConfigKey& key);

    /**
     * Report a broadcast has been sent to a config owner to collect the data.
     */
    void noteBroadcastSent(const ConfigKey& key);

    /**
     * Report a config's metrics data has been dropped.
     */
    void noteDataDropped(const ConfigKey& key);

    /**
     * Report metrics data report has been sent.
     *
     * The report may be requested via StatsManager API, or through adb cmd.
     */
    void noteMetricsReportSent(const ConfigKey& key);

    /**
     * Report the size of output tuple of a condition.
     *
     * Note: only report when the condition has an output dimension, and the tuple
     * count > kDimensionKeySizeSoftLimit.
     *
     * [key]: The config key that this condition belongs to.
     * [name]: The name of the condition.
     * [size]: The output tuple size.
     */
    void noteConditionDimensionSize(const ConfigKey& key, const std::string& name, int size);

    /**
     * Report the size of output tuple of a metric.
     *
     * Note: only report when the metric has an output dimension, and the tuple
     * count > kDimensionKeySizeSoftLimit.
     *
     * [key]: The config key that this metric belongs to.
     * [name]: The name of the metric.
     * [size]: The output tuple size.
     */
    void noteMetricDimensionSize(const ConfigKey& key, const std::string& name, int size);

    /**
     * Report a matcher has been matched.
     *
     * [key]: The config key that this matcher belongs to.
     * [name]: The name of the matcher.
     */
    void noteMatcherMatched(const ConfigKey& key, const std::string& name);

    /**
     * Report an atom event has been logged.
     */
    void noteAtomLogged(int atomId, int32_t timeSec);

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

private:
    StatsdStats();

    mutable std::mutex mLock;

    int32_t mStartTimeSec;

    // The stats about the configs that are still in use.
    std::map<const ConfigKey, StatsdStatsReport_ConfigStats> mConfigStats;

    // Stores the stats for the configs that are no longer in use.
    std::vector<const StatsdStatsReport_ConfigStats> mIceBox;

    // Stores the number of output tuple of condition trackers when it's bigger than
    // kDimensionKeySizeSoftLimit. When you see the number is kDimensionKeySizeHardLimit +1,
    // it means some data has been dropped.
    std::map<const ConfigKey, std::map<const std::string, int>> mConditionStats;

    // Stores the number of output tuple of metric producers when it's bigger than
    // kDimensionKeySizeSoftLimit. When you see the number is kDimensionKeySizeHardLimit +1,
    // it means some data has been dropped.
    std::map<const ConfigKey, std::map<const std::string, int>> mMetricsStats;

    // Stores the number of times a pushed atom is logged.
    // The size of the vector is the largest pushed atom id in atoms.proto + 1. Atoms
    // out of that range will be dropped (it's either pulled atoms or test atoms).
    // This is a vector, not a map because it will be accessed A LOT -- for each stats log.
    std::vector<int> mPushedAtomStats;

    // Stores how many times a matcher have been matched.
    std::map<const ConfigKey, std::map<const std::string, int>> mMatcherStats;

    void noteConfigRemovedInternalLocked(const ConfigKey& key);

    void resetInternalLocked();

    void addSubStatsToConfig(const ConfigKey& key, StatsdStatsReport_ConfigStats& configStats);

    FRIEND_TEST(StatsdStatsTest, TestValidConfigAdd);
    FRIEND_TEST(StatsdStatsTest, TestInvalidConfigAdd);
    FRIEND_TEST(StatsdStatsTest, TestConfigRemove);
    FRIEND_TEST(StatsdStatsTest, TestSubStats);
    FRIEND_TEST(StatsdStatsTest, TestAtomLog);
};

}  // namespace statsd
}  // namespace os
}  // namespace android