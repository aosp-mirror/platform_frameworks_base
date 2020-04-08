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

#include "FieldValue.h"
#include "HashableDimensionKey.h"
#include "benchmark/benchmark.h"
#include "logd/LogEvent.h"
#include "metric_util.h"
#include "stats_event.h"
#include "stats_log_util.h"

namespace android {
namespace os {
namespace statsd {

using std::vector;

static void createLogEventAndLink(LogEvent* event, Metric2Condition *link) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, 1);
    AStatsEvent_overwriteTimestamp(statsEvent, 100000);

    std::vector<int> attributionUids = {100, 100};
    std::vector<string> attributionTags = {"LOCATION", "LOCATION"};
    writeAttribution(statsEvent, attributionUids, attributionTags);

    AStatsEvent_writeFloat(statsEvent, 3.2f);
    AStatsEvent_writeString(statsEvent, "LOCATION");
    AStatsEvent_writeInt64(statsEvent, 990);

    parseStatsEventToLogEvent(statsEvent, event);

    link->conditionId = 1;

    FieldMatcher field_matcher;
    field_matcher.set_field(event->GetTagId());
    auto child = field_matcher.add_child();
    child->set_field(1);
    child->set_position(FIRST);
    child->add_child()->set_field(1);

    translateFieldMatcher(field_matcher, &link->metricFields);
    field_matcher.set_field(event->GetTagId() + 1);
    translateFieldMatcher(field_matcher, &link->conditionFields);
}

static void BM_GetDimensionInCondition(benchmark::State& state) {
    Metric2Condition link;
    LogEvent event(/*uid=*/0, /*pid=*/0);
    createLogEventAndLink(&event, &link);

    while (state.KeepRunning()) {
        HashableDimensionKey output;
        getDimensionForCondition(event.getValues(), link, &output);
    }
}

BENCHMARK(BM_GetDimensionInCondition);


}  //  namespace statsd
}  //  namespace os
}  //  namespace android
