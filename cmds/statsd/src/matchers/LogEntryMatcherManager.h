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

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include <log/logprint.h>
#include <log/log_read.h>
#include <set>
#include <vector>
#include <unordered_map>

using std::unordered_map;
using std::string;

namespace android {
namespace os {
namespace statsd {

/**
 * Keeps track per log entry which simple log entry matchers match.
 */
class LogEntryMatcherManager {
public:
    LogEntryMatcherManager();

    ~LogEntryMatcherManager() {};

    static bool matches(const LogEntryMatcher &matcher, const int tagId,
                        const unordered_map<int, long> &intMap,
                        const unordered_map<int, string> &strMap,
                        const unordered_map<int, float> &floatMap,
                        const unordered_map<int, bool> &boolMap);

    static bool matchesSimple(const SimpleLogEntryMatcher &simpleMatcher,
                              const int tagId,
                              const unordered_map<int, long> &intMap,
                              const unordered_map<int, string> &strMap,
                              const unordered_map<int, float> &floatMap,
                              const unordered_map<int, bool> &boolMap);
};

} // namespace statsd
} // namespace os
} // namespace android
#endif //LOG_ENTRY_MATCHER_MANAGER_H
