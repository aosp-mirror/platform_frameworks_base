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

#pragma once

#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include <sstream>
#include "logd/LogReader.h"

#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

#define DEFAULT_DIMENSION_KEY ""

// Minimum bucket size in seconds
const long kMinBucketSizeSec = 5 * 60;

typedef std::string HashableDimensionKey;

typedef std::map<std::string, HashableDimensionKey> ConditionKey;

typedef std::unordered_map<HashableDimensionKey, int64_t> DimToValMap;

/*
 * In memory rep for LogEvent. Uses much less memory than LogEvent
 */
typedef struct EventKV {
    std::vector<KeyValuePair> kv;
    string ToString() const {
        std::ostringstream result;
        result << "{ ";
        const size_t N = kv.size();
        for (size_t i = 0; i < N; i++) {
            result << " ";
            result << (i + 1);
            result << "->";
            const auto& pair = kv[i];
            if (pair.has_value_int()) {
                result << pair.value_int();
            } else if (pair.has_value_long()) {
                result << pair.value_long();
            } else if (pair.has_value_float()) {
                result << pair.value_float();
            } else if (pair.has_value_str()) {
                result << pair.value_str().c_str();
            }
        }
        result << " }";
        return result.str();
    }
} EventKV;

typedef std::unordered_map<HashableDimensionKey, std::shared_ptr<EventKV>> DimToEventKVMap;

EventMetricData parse(log_msg msg);

int getTagId(log_msg msg);

std::string getHashableKey(std::vector<KeyValuePair> key);

}  // namespace statsd
}  // namespace os
}  // namespace android
