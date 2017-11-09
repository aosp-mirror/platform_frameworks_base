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

#include "logd/LogReader.h"
#include "storage/DropboxWriter.h"

#include <log/logprint.h>
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

#define DEFAULT_DIMENSION_KEY ""
#define MATCHER_NOT_FOUND -2

typedef std::string HashableDimensionKey;

typedef std::map<std::string, HashableDimensionKey> ConditionKey;

// TODO: For P, change int to int64_t.
// TODO: Should HashableDimensionKey be marked here as const?
typedef std::unordered_map<HashableDimensionKey, int> DimToValMap;

EventMetricData parse(log_msg msg);

int getTagId(log_msg msg);

std::string getHashableKey(std::vector<KeyValuePair> key);
}  // namespace statsd
}  // namespace os
}  // namespace android
