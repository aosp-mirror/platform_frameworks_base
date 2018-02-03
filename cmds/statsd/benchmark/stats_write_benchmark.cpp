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
#include "benchmark/benchmark.h"
#include <statslog.h>

namespace android {
namespace os {
namespace statsd {

static void BM_StatsWrite(benchmark::State& state) {
    const char* reason = "test";
    int64_t boot_end_time = 1234567;
    int64_t total_duration = 100;
    int64_t bootloader_duration = 10;
    int64_t time_since_last_boot = 99999999;
    while (state.KeepRunning()) {
        android::util::stats_write(
                android::util::BOOT_SEQUENCE_REPORTED, reason, reason,
                boot_end_time, total_duration, bootloader_duration, time_since_last_boot);
        total_duration++;
    }
}
BENCHMARK(BM_StatsWrite);

}  //  namespace statsd
}  //  namespace os
}  //  namespace android
