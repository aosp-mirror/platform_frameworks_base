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

#include <utils/String16.h>
#include "StatsPuller.h"

namespace android {
namespace os {
namespace statsd {

/**
 * Reads /proc/uid_cputime/show_uid_stat which has the line format:
 *
 * uid: user_time_micro_seconds system_time_micro_seconds
 *
 * This provides the time a UID's processes spent executing in user-space and kernel-space.
 * The file contains a monotonically increasing count of time for a single boot.
 */
class KernelUidCpuClusterTimeReader : public StatsPuller {
 public:
    KernelUidCpuClusterTimeReader();
    bool PullInternal(vector<std::shared_ptr<LogEvent>>* data) override;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
