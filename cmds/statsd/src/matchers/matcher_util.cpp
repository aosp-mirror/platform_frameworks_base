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

#include "matcher_util.h"
#include <cutils/log.h>
#include <log/event_tag_map.h>
#include <log/log_event_list.h>
#include <log/logprint.h>
#include <utils/Errors.h>
#include <unordered_map>
#include "LogMatchingTracker.h"
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "stats_util.h"

using std::set;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

LogEventWrapper parseLogEvent(log_msg msg) {
    LogEventWrapper wrapper;
    wrapper.timestamp_ns = msg.entry_v1.sec * NS_PER_SEC + msg.entry_v1.nsec;
    wrapper.tagId = getTagId(msg);

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
                        wrapper.intMap[key] = elem.data.int32;
                    }
                    index++;
                    break;
                case EVENT_TYPE_FLOAT:
                    if (index % 2 == 1) {
                        wrapper.floatMap[key] = elem.data.float32;
                    }
                    index++;
                    break;
                case EVENT_TYPE_STRING:
                    if (index % 2 == 1) {
                        // without explicit calling string() constructor, there will be an
                        // additional 0 in the end of the string.
                        wrapper.strMap[key] = string(elem.data.string);
                    }
                    index++;
                    break;
                case EVENT_TYPE_LONG:
                    if (index % 2 == 1) {
                        wrapper.intMap[key] = elem.data.int64;
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

    return wrapper;
}

bool combinationMatch(const vector<int>& children, const LogicalOperation& operation,
                      const vector<MatchingState>& matcherResults) {
    bool matched;
    switch (operation) {
        case LogicalOperation::AND: {
            matched = true;
            for (const int childIndex : children) {
                if (matcherResults[childIndex] != MatchingState::kMatched) {
                    matched = false;
                    break;
                }
            }
            break;
        }
        case LogicalOperation::OR: {
            matched = false;
            for (const int childIndex : children) {
                if (matcherResults[childIndex] == MatchingState::kMatched) {
                    matched = true;
                    break;
                }
            }
            break;
        }
        case LogicalOperation::NOT:
            matched = matcherResults[children[0]] == MatchingState::kNotMatched;
            break;
        case LogicalOperation::NAND:
            matched = false;
            for (const int childIndex : children) {
                if (matcherResults[childIndex] != MatchingState::kMatched) {
                    matched = true;
                    break;
                }
            }
            break;
        case LogicalOperation::NOR:
            matched = true;
            for (const int childIndex : children) {
                if (matcherResults[childIndex] == MatchingState::kMatched) {
                    matched = false;
                    break;
                }
            }
            break;
    }
    return matched;
}

bool matchesSimple(const SimpleLogEntryMatcher& simpleMatcher, const LogEventWrapper& event) {
    const int tagId = event.tagId;
    const unordered_map<int, long>& intMap = event.intMap;
    const unordered_map<int, string>& strMap = event.strMap;
    const unordered_map<int, float>& floatMap = event.floatMap;
    const unordered_map<int, bool>& boolMap = event.boolMap;

    for (int i = 0; i < simpleMatcher.tag_size(); i++) {
        if (simpleMatcher.tag(i) != tagId) {
            continue;
        }

        // now see if this event is interesting to us -- matches ALL the matchers
        // defined in the metrics.
        bool allMatched = true;
        for (int j = 0; j < simpleMatcher.key_value_matcher_size(); j++) {
            auto cur = simpleMatcher.key_value_matcher(j);

            // TODO: Check if this key is a magic key (eg package name).
            int key = cur.key_matcher().key();

            switch (cur.value_matcher_case()) {
                case KeyValueMatcher::ValueMatcherCase::kEqString: {
                    auto it = strMap.find(key);
                    if (it == strMap.end() || cur.eq_string().compare(it->second) != 0) {
                        allMatched = false;
                    }
                    break;
                }
                case KeyValueMatcher::ValueMatcherCase::kEqInt: {
                    auto it = intMap.find(key);
                    if (it == intMap.end() || cur.eq_int() != it->second) {
                        allMatched = false;
                    }
                    break;
                }
                case KeyValueMatcher::ValueMatcherCase::kEqBool: {
                    auto it = boolMap.find(key);
                    if (it == boolMap.end() || cur.eq_bool() != it->second) {
                        allMatched = false;
                    }
                    break;
                }
                    // Begin numeric comparisons
                case KeyValueMatcher::ValueMatcherCase::kLtInt: {
                    auto it = intMap.find(key);
                    if (it == intMap.end() || cur.lt_int() <= it->second) {
                        allMatched = false;
                    }
                    break;
                }
                case KeyValueMatcher::ValueMatcherCase::kGtInt: {
                    auto it = intMap.find(key);
                    if (it == intMap.end() || cur.gt_int() >= it->second) {
                        allMatched = false;
                    }
                    break;
                }
                case KeyValueMatcher::ValueMatcherCase::kLtFloat: {
                    auto it = floatMap.find(key);
                    if (it == floatMap.end() || cur.lt_float() <= it->second) {
                        allMatched = false;
                    }
                    break;
                }
                case KeyValueMatcher::ValueMatcherCase::kGtFloat: {
                    auto it = floatMap.find(key);
                    if (it == floatMap.end() || cur.gt_float() >= it->second) {
                        allMatched = false;
                    }
                    break;
                }
                // Begin comparisons with equality
                case KeyValueMatcher::ValueMatcherCase::kLteInt: {
                    auto it = intMap.find(key);
                    if (it == intMap.end() || cur.lte_int() < it->second) {
                        allMatched = false;
                    }
                    break;
                }
                case KeyValueMatcher::ValueMatcherCase::kGteInt: {
                    auto it = intMap.find(key);
                    if (it == intMap.end() || cur.gte_int() > it->second) {
                        allMatched = false;
                    }
                    break;
                }
                case KeyValueMatcher::ValueMatcherCase::VALUE_MATCHER_NOT_SET:
                    // If value matcher is not present, assume that we match.
                    break;
            }
        }

        if (allMatched) {
            return true;
        }
    }
    return false;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
