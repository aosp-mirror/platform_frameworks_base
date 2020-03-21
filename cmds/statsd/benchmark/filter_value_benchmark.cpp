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
#include "FieldValue.h"
#include "HashableDimensionKey.h"
#include "logd/LogEvent.h"
#include "stats_log_util.h"
#include "stats_event.h"

namespace android {
namespace os {
namespace statsd {

using std::vector;

static void createLogEventAndMatcher(LogEvent* event, FieldMatcher* field_matcher) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, 1);
    AStatsEvent_overwriteTimestamp(statsEvent, 100000);

    std::vector<int> attributionUids = {100, 100};
    std::vector<string> attributionTags = {"LOCATION", "LOCATION"};

    vector<const char*> cTags(attributionTags.size());
    for (int i = 0; i < cTags.size(); i++) {
        cTags[i] = attributionTags[i].c_str();
    }

    AStatsEvent_writeAttributionChain(statsEvent,
                                      reinterpret_cast<const uint32_t*>(attributionUids.data()),
                                      cTags.data(), attributionUids.size());
    AStatsEvent_writeFloat(statsEvent, 3.2f);
    AStatsEvent_writeString(statsEvent, "LOCATION");
    AStatsEvent_writeInt64(statsEvent, 990);
    AStatsEvent_build(statsEvent);

    size_t size;
    uint8_t* buf = AStatsEvent_getBuffer(statsEvent, &size);
    event->parseBuffer(buf, size);
    AStatsEvent_release(statsEvent);

    field_matcher->set_field(1);
    auto child = field_matcher->add_child();
    child->set_field(1);
    child->set_position(FIRST);
    child->add_child()->set_field(1);
}

static void BM_FilterValue(benchmark::State& state) {
    LogEvent event(/*uid=*/0, /*pid=*/0);
    FieldMatcher field_matcher;
    createLogEventAndMatcher(&event, &field_matcher);

    std::vector<Matcher> matchers;
    translateFieldMatcher(field_matcher, &matchers);

    while (state.KeepRunning()) {
        HashableDimensionKey output;
        filterValues(matchers, event.getValues(), &output);
    }
}

BENCHMARK(BM_FilterValue);

}  //  namespace statsd
}  //  namespace os
}  //  namespace android
