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
#include <vector>
#include "benchmark/benchmark.h"
#include "logd/LogEvent.h"
#include "stats_event.h"

namespace android {
namespace os {
namespace statsd {

static size_t createAndParseStatsEvent(uint8_t* msg) {
    struct stats_event* event = stats_event_obtain();
    stats_event_set_atom_id(event, 100);
    stats_event_write_int32(event, 2);
    stats_event_write_float(event, 2.0);
    stats_event_build(event);

    size_t size;
    uint8_t* buf = stats_event_get_buffer(event, &size);
    memcpy(msg, buf, size);
    return size;
}

static void BM_LogEventCreation(benchmark::State& state) {
    uint8_t msg[LOGGER_ENTRY_MAX_PAYLOAD];
    size_t size = createAndParseStatsEvent(msg);
    while (state.KeepRunning()) {
        benchmark::DoNotOptimize(LogEvent(msg, size, /*uid=*/ 1000));
    }
}
BENCHMARK(BM_LogEventCreation);

}  //  namespace statsd
}  //  namespace os
}  //  namespace android
