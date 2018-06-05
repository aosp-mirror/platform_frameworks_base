/*
 * Copyright (C) 2018 The Android Open Source Project
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

#include <inttypes.h>

namespace android {
namespace os {
namespace statsd {

class ConfigKey;
class PerfprofdDetails;  // Declared in statsd_config.pb.h

// Starts the collection of a Perfprofd trace with the given |config|.
// The trace is uploaded to Dropbox by the perfprofd service once done.
// This method returns immediately after passing the config and does NOT wait
// for the full duration of the trace.
bool CollectPerfprofdTraceAndUploadToDropbox(const PerfprofdDetails& config,
                                             int64_t alert_id,
                                             const ConfigKey& configKey);

}  // namespace statsd
}  // namespace os
}  // namespace android
