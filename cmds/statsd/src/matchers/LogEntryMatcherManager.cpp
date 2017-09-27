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

#include "LogEntryMatcherManager.h"
#include <log/event_tag_map.h>
#include <log/logprint.h>
#include <utils/Errors.h>
#include <cutils/log.h>
#include <unordered_map>
#include <frameworks/base/cmds/statsd/src/statsd_config.pb.h>

using std::unordered_map;
using std::string;

namespace android {
namespace os {
namespace statsd {

bool LogEntryMatcherManager::matches(const LogEntryMatcher &matcher, const int tagId,
                                     const unordered_map<int, long> &intMap,
                                     const unordered_map<int, string> &strMap,
                                     const unordered_map<int, float> &floatMap,
                                     const unordered_map<int, bool> &boolMap) {
    if (matcher.has_combination()) { // Need to evaluate composite matching
        switch (matcher.combination().operation()) {
            case LogicalOperation::AND:
                for (auto nestedMatcher : matcher.combination().matcher()) {
                    if (!matches(nestedMatcher, tagId, intMap, strMap, floatMap, boolMap)) {
                        return false; // return false if any nested matcher is false;
                    }
                }
                return true; // Otherwise, return true.
            case LogicalOperation::OR:
                for (auto nestedMatcher : matcher.combination().matcher()) {
                    if (matches(nestedMatcher, tagId, intMap, strMap, floatMap, boolMap)) {
                        return true; // return true if any nested matcher is true;
                    }
                }
                return false;
            case LogicalOperation::NOT:
                return !matches(matcher.combination().matcher(0),  tagId, intMap, strMap, floatMap,
                                boolMap);

            // Case NAND is just inverting the return statement of AND
            case LogicalOperation::NAND:
                for (auto nestedMatcher : matcher.combination().matcher()) {
                    auto simple = nestedMatcher.simple_log_entry_matcher();
                    if (!matches(nestedMatcher, tagId, intMap, strMap, floatMap, boolMap)) {
                        return true; // return false if any nested matcher is false;
                    }
                }
                return false; // Otherwise, return true.
            case LogicalOperation::NOR:
                for (auto nestedMatcher : matcher.combination().matcher()) {
                    if (matches(nestedMatcher, tagId, intMap, strMap, floatMap, boolMap)) {
                        return false; // return true if any nested matcher is true;
                    }
                }
                return true;
        }
        return false;
    } else {
        return matchesSimple(matcher.simple_log_entry_matcher(), tagId, intMap, strMap, floatMap,
                             boolMap);
    }
}

bool LogEntryMatcherManager::matchesSimple(const SimpleLogEntryMatcher &simpleMatcher,
                                           const int tagId,
                                           const unordered_map<int, long> &intMap,
                                           const unordered_map<int, string> &strMap,
                                           const unordered_map<int, float> &floatMap,
                                           const unordered_map<int, bool> &boolMap) {
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

} // namespace statsd
} // namespace os
} // namespace android
