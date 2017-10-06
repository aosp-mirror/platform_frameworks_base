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

#include <log/log_event_list.h>
#include "stats_util.h"

namespace android {
namespace os {
namespace statsd {

static inline uint32_t get4LE(const char* src) {
    return src[0] | (src[1] << 8) | (src[2] << 16) | (src[3] << 24);
}

int getTagId(log_msg msg) {
    return get4LE(msg.msg());
}

EventMetricData parse(log_msg msg) {
    // dump all statsd logs to dropbox for now.
    // TODO: Add filtering, aggregation, etc.
    EventMetricData eventMetricData;

    // set tag.
    int tag = getTagId(msg);
    // TODO: Replace the following line when we can serialize on the fly.
    // eventMetricData.set_tag(tag);

    // set timestamp of the event.
    eventMetricData.set_timestamp_nanos(msg.entry_v1.sec * NS_PER_SEC + msg.entry_v1.nsec);

    // start iterating k,v pairs.
    android_log_context context =
            create_android_log_parser(const_cast<log_msg*>(&msg)->msg() + sizeof(uint32_t),
                                      const_cast<log_msg*>(&msg)->len() - sizeof(uint32_t));
    android_log_list_element elem;

    if (context) {
        memset(&elem, 0, sizeof(elem));
        size_t index = 0;
        int32_t key = -1;

        do {
            elem = android_log_read_next(context);
            switch ((int)elem.type) {
                case EVENT_TYPE_INT:
                    if (index % 2 == 0) {
                        key = elem.data.int32;
                    } else {
                        // TODO: Fix the following lines when we can serialize on the fly.
                        /*
                        int32_t val = elem.data.int32;
                        KeyValuePair* keyValuePair = eventMetricData.add_key_value_pair();
                        keyValuePair->set_key(key);
                        keyValuePair->set_value_int(val);
                        */
                    }
                    index++;
                    break;
                case EVENT_TYPE_FLOAT:
                    if (index % 2 == 1) {
                        // TODO: Fix the following lines when we can serialize on the fly.
                        /*
                        float val = elem.data.float32;
                        KeyValuePair* keyValuePair = eventMetricData.add_key_value_pair();
                        keyValuePair->set_key(key);
                        keyValuePair->set_value_float(val);
                        */
                    }
                    index++;
                    break;
                case EVENT_TYPE_STRING:
                    if (index % 2 == 1) {
                        // TODO: Fix the following lines when we can serialize on the fly.
                        /*
                        char* val = elem.data.string;
                        KeyValuePair* keyValuePair = eventMetricData.add_key_value_pair();
                        keyValuePair->set_key(key);
                        keyValuePair->set_value_str(val);
                        */
                    }
                    index++;
                    break;
                case EVENT_TYPE_LONG:
                    if (index % 2 == 1) {
                        // TODO: Fix the following lines when we can serialize on the fly.
                        /*
                        int64_t val = elem.data.int64;
                        KeyValuePair* keyValuePair = eventMetricData.add_key_value_pair();
                        keyValuePair->set_key(key);
                        keyValuePair->set_value_int(val);
                        */
                    }
                    index++;
                    break;
                case EVENT_TYPE_LIST:
                    break;
                case EVENT_TYPE_LIST_STOP:
                    break;
                case EVENT_TYPE_UNKNOWN:
                    break;
                default:
                    elem.complete = true;
                    break;
            }

            if (elem.complete) {
                break;
            }
        } while ((elem.type != EVENT_TYPE_UNKNOWN) && !elem.complete);

        android_log_destroy(&context);
    }

    return eventMetricData;
}

StatsdConfig buildFakeConfig() {
    // HACK: Hard code a test metric for counting screen on events...
    StatsdConfig config;
    config.set_config_id(12345L);

    // One count metric to count screen on
    CountMetric* metric = config.add_count_metric();
    metric->set_metric_id(20150717L);
    metric->set_what("SCREEN_IS_ON");
    metric->mutable_bucket()->set_bucket_size_millis(30 * 1000L);

    // One count metric to count PHOTO_CHANGE_OR_CHROME_CRASH
    metric = config.add_count_metric();
    metric->set_metric_id(20150718L);
    metric->set_what("PHOTO_PROCESS_STATE_CHANGE");
    metric->mutable_bucket()->set_bucket_size_millis(60 * 1000L);
    metric->set_condition("SCREEN_IS_ON");


    LogEntryMatcher* eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_ON");

    SimpleLogEntryMatcher* simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->add_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            2 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);



    eventMatcher = config.add_log_entry_matcher();
    eventMatcher->set_name("SCREEN_IS_OFF");

    simpleLogEntryMatcher = eventMatcher->mutable_simple_log_entry_matcher();
    simpleLogEntryMatcher->add_tag(2 /*SCREEN_STATE_CHANGE*/);
    simpleLogEntryMatcher->add_key_value_matcher()->mutable_key_matcher()->set_key(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    simpleLogEntryMatcher->mutable_key_value_matcher(0)->set_eq_int(
            1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_OFF*/);



    LogEntryMatcher* procEventMatcher = config.add_log_entry_matcher();
    procEventMatcher->set_name("PHOTO_CRASH");

    SimpleLogEntryMatcher* simpleLogMatcher2 = procEventMatcher->mutable_simple_log_entry_matcher();
    simpleLogMatcher2->add_tag(1112 /*PROCESS_STATE_CHANGE*/);
    KeyValueMatcher* keyValueMatcher = simpleLogMatcher2->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(
                1002 /*pkg*/);
    keyValueMatcher->set_eq_string(
            "com.google.android.apps.photos" /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    keyValueMatcher = simpleLogMatcher2->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(
                                   1 /*SCREEN_STATE_CHANGE__DISPLAY_STATE*/);
    keyValueMatcher->set_eq_int(2);


    procEventMatcher = config.add_log_entry_matcher();
    procEventMatcher->set_name("PHOTO_START");

    simpleLogMatcher2 = procEventMatcher->mutable_simple_log_entry_matcher();
    simpleLogMatcher2->add_tag(1112 /*PROCESS_STATE_CHANGE*/);
    keyValueMatcher = simpleLogMatcher2->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(
                1002 /*pkg*/);
    keyValueMatcher->set_eq_string(
            "com.google.android.apps.photos" /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    keyValueMatcher = simpleLogMatcher2->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(
                                   1 /*STATE*/);
    keyValueMatcher->set_eq_int(1);


    procEventMatcher = config.add_log_entry_matcher();
    procEventMatcher->set_name("PHOTO_PROCESS_STATE_CHANGE");
    LogEntryMatcher_Combination* combinationMatcher = procEventMatcher->mutable_combination();
    combinationMatcher->set_operation(LogicalOperation::OR);
    combinationMatcher->add_matcher("PHOTO_START");
    combinationMatcher->add_matcher("PHOTO_CRASH");


    procEventMatcher = config.add_log_entry_matcher();
    procEventMatcher->set_name("CHROME_CRASH");

    simpleLogMatcher2 = procEventMatcher->mutable_simple_log_entry_matcher();
    simpleLogMatcher2->add_tag(1112 /*PROCESS_STATE_CHANGE*/);
    keyValueMatcher = simpleLogMatcher2->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(
                1002 /*pkg*/);
    keyValueMatcher->set_eq_string(
            "com.android.chrome" /*SCREEN_STATE_CHANGE__DISPLAY_STATE__STATE_ON*/);

    keyValueMatcher = simpleLogMatcher2->add_key_value_matcher();
    keyValueMatcher->mutable_key_matcher()->set_key(
                                   1 /*STATE*/);
    keyValueMatcher->set_eq_int(2);



    procEventMatcher = config.add_log_entry_matcher();
    procEventMatcher->set_name("PHOTO_CHANGE_OR_CHROME_CRASH");
    combinationMatcher = procEventMatcher->mutable_combination();
    combinationMatcher->set_operation(LogicalOperation::OR);
    combinationMatcher->add_matcher("PHOTO_PROCESS_STATE_CHANGE");
    combinationMatcher->add_matcher("CHROME_CRASH");



    Condition* condition = config.add_condition();
    condition->set_name("SCREEN_IS_ON");
    SimpleCondition* simpleCondition = condition->mutable_simple_condition();
    simpleCondition->set_start("SCREEN_IS_ON");
    simpleCondition->set_stop("SCREEN_IS_OFF");


    condition = config.add_condition();
        condition->set_name("PHOTO_STARTED");

        simpleCondition = condition->mutable_simple_condition();
        simpleCondition->set_start("PHOTO_START");
        simpleCondition->set_stop("PHOTO_CRASH");


    condition = config.add_condition();
    condition->set_name("SCREEN_IS_OFF");

    simpleCondition = condition->mutable_simple_condition();
    simpleCondition->set_start("SCREEN_IS_OFF");
    simpleCondition->set_stop("SCREEN_IS_ON");


    condition = config.add_condition();
    condition->set_name("SCREEN_IS_EITHER_ON_OFF");

    Condition_Combination* combination = condition->mutable_combination();
    combination->set_operation(LogicalOperation::OR);
    combination->add_condition("SCREEN_IS_ON");
    combination->add_condition("SCREEN_IS_OFF");


    condition = config.add_condition();
    condition->set_name("SCREEN_IS_NEITHER_ON_OFF");

    combination = condition->mutable_combination();
    combination->set_operation(LogicalOperation::NOR);
    combination->add_condition("SCREEN_IS_ON");
    combination->add_condition("SCREEN_IS_OFF");

    return config;
}


}  // namespace statsd
}  // namespace os
}  // namespace android
