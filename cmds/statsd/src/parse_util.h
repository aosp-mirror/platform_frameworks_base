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
#ifndef PARSE_UTIL_H
#define PARSE_UTIL_H

#include "DropboxWriter.h"
#include "LogReader.h"

#include <log/logprint.h>

namespace android {
namespace os {
namespace statsd {

EventMetricData parse(log_msg msg);

int getTagId(log_msg msg);

}  // namespace statsd
}  // namespace os
}  // namespace android

#endif  // PARSE_UTIL_H
