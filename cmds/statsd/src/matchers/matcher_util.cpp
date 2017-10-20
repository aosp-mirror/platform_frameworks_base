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

#include "Log.h"

#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "matchers/LogMatchingTracker.h"
#include "matchers/matcher_util.h"
#include "stats_util.h"

#include <log/event_tag_map.h>
#include <log/log_event_list.h>
#include <log/logprint.h>
#include <utils/Errors.h>

#include <sstream>
#include <unordered_map>

using std::ostringstream;
using std::set;
using std::string;
using std::unordered_map;
using std::vector;

namespace android {
namespace os {
namespace statsd {

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

bool matchesSimple(const SimpleLogEntryMatcher& simpleMatcher, const LogEvent& event) {
    const int tagId = event.GetTagId();

    if (simpleMatcher.tag() != tagId) {
        return false;
    }
    // now see if this event is interesting to us -- matches ALL the matchers
    // defined in the metrics.
    bool allMatched = true;
    for (int j = 0; allMatched && j < simpleMatcher.key_value_matcher_size(); j++) {
        auto cur = simpleMatcher.key_value_matcher(j);

        // TODO: Check if this key is a magic key (eg package name).
        // TODO: Maybe make packages a different type in the config?
        int key = cur.key_matcher().key();

        const KeyValueMatcher::ValueMatcherCase matcherCase = cur.value_matcher_case();
        if (matcherCase == KeyValueMatcher::ValueMatcherCase::kEqString) {
            // String fields
            status_t err = NO_ERROR;
            const char* val = event.GetString(key, &err);
            if (err == NO_ERROR && val != NULL) {
                if (!(cur.eq_string() == val)) {
                    allMatched = false;
                }
            }
        } else if (matcherCase == KeyValueMatcher::ValueMatcherCase::kEqInt ||
                   matcherCase == KeyValueMatcher::ValueMatcherCase::kLtInt ||
                   matcherCase == KeyValueMatcher::ValueMatcherCase::kGtInt ||
                   matcherCase == KeyValueMatcher::ValueMatcherCase::kLteInt ||
                   matcherCase == KeyValueMatcher::ValueMatcherCase::kGteInt) {
            // Integer fields
            status_t err = NO_ERROR;
            int64_t val = event.GetLong(key, &err);
            if (err == NO_ERROR) {
                if (matcherCase == KeyValueMatcher::ValueMatcherCase::kEqInt) {
                    if (!(val == cur.eq_int())) {
                        allMatched = false;
                    }
                } else if (matcherCase == KeyValueMatcher::ValueMatcherCase::kLtInt) {
                    if (!(val < cur.lt_int())) {
                        allMatched = false;
                    }
                } else if (matcherCase == KeyValueMatcher::ValueMatcherCase::kGtInt) {
                    if (!(val > cur.gt_int())) {
                        allMatched = false;
                    }
                } else if (matcherCase == KeyValueMatcher::ValueMatcherCase::kLteInt) {
                    if (!(val <= cur.lte_int())) {
                        allMatched = false;
                    }
                } else if (matcherCase == KeyValueMatcher::ValueMatcherCase::kGteInt) {
                    if (!(val >= cur.gte_int())) {
                        allMatched = false;
                    }
                }
            }
            break;
        } else if (matcherCase == KeyValueMatcher::ValueMatcherCase::kEqBool) {
            // Boolean fields
            status_t err = NO_ERROR;
            bool val = event.GetBool(key, &err);
            if (err == NO_ERROR) {
                if (!(cur.eq_bool() == val)) {
                    allMatched = false;
                }
            }
        } else if (matcherCase == KeyValueMatcher::ValueMatcherCase::kLtFloat ||
                   matcherCase == KeyValueMatcher::ValueMatcherCase::kGtFloat) {
            // Float fields
            status_t err = NO_ERROR;
            bool val = event.GetFloat(key, &err);
            if (err == NO_ERROR) {
                if (matcherCase == KeyValueMatcher::ValueMatcherCase::kLtFloat) {
                    if (!(cur.lt_float() <= val)) {
                        allMatched = false;
                    }
                } else if (matcherCase == KeyValueMatcher::ValueMatcherCase::kGtFloat) {
                    if (!(cur.gt_float() >= val)) {
                        allMatched = false;
                    }
                }
            }
        } else {
            // If value matcher is not present, assume that we match.
        }
    }
    return allMatched;
}

vector<KeyValuePair> getDimensionKey(const LogEvent& event,
                                     const std::vector<KeyMatcher>& dimensions) {
    vector<KeyValuePair> key;
    key.reserve(dimensions.size());
    for (const KeyMatcher& dimension : dimensions) {
        KeyValuePair k = event.GetKeyValueProto(dimension.key());
        key.push_back(k);
    }
    return key;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
