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
#include <parse_util.h>

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
    //eventMetricData.set_tag(tag);

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
}  // namespace statsd
}  // namespace os
}  // namespace android
