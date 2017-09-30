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

#ifndef LOG_ENTRY_MATCHER_MANAGER_H
#define LOG_ENTRY_MATCHER_MANAGER_H

#include <log/log_read.h>
#include <log/logprint.h>
#include <set>
#include <unordered_map>
#include <vector>
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

using std::string;
using std::unordered_map;

namespace android {
namespace os {
namespace statsd {

typedef struct {
    int tagId;
    long timestamp_ns;
    std::unordered_map<int, long> intMap;
    std::unordered_map<int, std::string> strMap;
    std::unordered_map<int, bool> boolMap;
    std::unordered_map<int, float> floatMap;
} LogEventWrapper;

/**
 * Keeps track per log entry which simple log entry matchers match.
 */
class LogEntryMatcherManager {
public:
    LogEntryMatcherManager();

    ~LogEntryMatcherManager(){};

    static LogEventWrapper parseLogEvent(log_msg msg);

    static std::set<int> getTagIdsFromMatcher(const LogEntryMatcher& matcher);

    static bool matches(const LogEntryMatcher& matcher, const LogEventWrapper& wrapper);

    static bool matchesSimple(const SimpleLogEntryMatcher& simpleMatcher,
                              const LogEventWrapper& wrapper);
};

}  // namespace statsd
}  // namespace os
}  // namespace android
#endif  // LOG_ENTRY_MATCHER_MANAGER_H
