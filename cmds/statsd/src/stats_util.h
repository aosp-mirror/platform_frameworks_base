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
#ifndef STATS_UTIL_H
#define STATS_UTIL_H

#include "logd/LogReader.h"
#include "storage/DropboxWriter.h"

#include <log/logprint.h>
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

namespace android {
namespace os {
namespace statsd {

#define DEFAULT_DIMENSION_KEY ""
#define MATCHER_NOT_FOUND -2
#define NANO_SECONDS_IN_A_SECOND (1000 * 1000 * 1000)

typedef std::string HashableDimensionKey;

EventMetricData parse(log_msg msg);

int getTagId(log_msg msg);

std::string getHashableKey(std::vector<KeyValuePair> key);
}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // STATS_UTIL_H
