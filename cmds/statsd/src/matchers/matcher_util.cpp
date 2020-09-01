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
#define DEBUG false  // STOPSHIP if true
#include "Log.h"

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "matchers/LogMatchingTracker.h"
#include "matchers/matcher_util.h"
#include "stats_util.h"

using std::set;
using std::string;
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
        case LogicalOperation::LOGICAL_OPERATION_UNSPECIFIED:
            matched = false;
            break;
    }
    return matched;
}

bool tryMatchString(const UidMap& uidMap, const FieldValue& fieldValue, const string& str_match) {
    if (isAttributionUidField(fieldValue) || isUidField(fieldValue)) {
        int uid = fieldValue.mValue.int_value;
        auto aidIt = UidMap::sAidToUidMapping.find(str_match);
        if (aidIt != UidMap::sAidToUidMapping.end()) {
            return ((int)aidIt->second) == uid;
        }
        std::set<string> packageNames = uidMap.getAppNamesFromUid(uid, true /* normalize*/);
        return packageNames.find(str_match) != packageNames.end();
    } else if (fieldValue.mValue.getType() == STRING) {
        return fieldValue.mValue.str_value == str_match;
    }
    return false;
}

bool matchesSimple(const UidMap& uidMap, const FieldValueMatcher& matcher,
                   const vector<FieldValue>& values, int start, int end, int depth) {
    if (depth > 2) {
        ALOGE("Depth > 3 not supported");
        return false;
    }

    if (start >= end) {
        return false;
    }

    // Filter by entry field first
    int newStart = -1;
    int newEnd = end;
    // because the fields are naturally sorted in the DFS order. we can safely
    // break when pos is larger than the one we are searching for.
    for (int i = start; i < end; i++) {
        int pos = values[i].mField.getPosAtDepth(depth);
        if (pos == matcher.field()) {
            if (newStart == -1) {
                newStart = i;
            }
            newEnd = i + 1;
        } else if (pos > matcher.field()) {
            break;
        }
    }

    // Now we have zoomed in to a new range
    start = newStart;
    end = newEnd;

    if (start == -1) {
        // No such field found.
        return false;
    }

    vector<pair<int, int>> ranges; // the ranges are for matching ANY position
    if (matcher.has_position()) {
        // Repeated fields position is stored as a node in the path.
        depth++;
        if (depth > 2) {
            return false;
        }
        switch (matcher.position()) {
            case Position::FIRST: {
                for (int i = start; i < end; i++) {
                    int pos = values[i].mField.getPosAtDepth(depth);
                    if (pos != 1) {
                        // Again, the log elements are stored in sorted order. so
                        // once the position is > 1, we break;
                        end = i;
                        break;
                    }
                }
                ranges.push_back(std::make_pair(start, end));
                break;
            }
            case Position::LAST: {
                // move the starting index to the first LAST field at the depth.
                for (int i = start; i < end; i++) {
                    if (values[i].mField.isLastPos(depth)) {
                        start = i;
                        break;
                    }
                }
                ranges.push_back(std::make_pair(start, end));
                break;
            }
            case Position::ANY: {
                // ANY means all the children matchers match in any of the sub trees, it's a match
                newStart = start;
                newEnd = end;
                // Here start is guaranteed to be a valid index.
                int currentPos = values[start].mField.getPosAtDepth(depth);
                // Now find all sub trees ranges.
                for (int i = start; i < end; i++) {
                    int newPos = values[i].mField.getPosAtDepth(depth);
                    if (newPos != currentPos) {
                        ranges.push_back(std::make_pair(newStart, i));
                        newStart = i;
                        currentPos = newPos;
                    }
                }
                ranges.push_back(std::make_pair(newStart, end));
                break;
            }
            case Position::ALL:
                ALOGE("Not supported: field matcher with ALL position.");
                break;
            case Position::POSITION_UNKNOWN:
                break;
        }
    } else {
        // No position
        ranges.push_back(std::make_pair(start, end));
    }
    // start and end are still pointing to the matched range.
    switch (matcher.value_matcher_case()) {
        case FieldValueMatcher::kMatchesTuple: {
            ++depth;
            // If any range matches all matchers, good.
            for (const auto& range : ranges) {
                bool matched = true;
                for (const auto& subMatcher : matcher.matches_tuple().field_value_matcher()) {
                    if (!matchesSimple(uidMap, subMatcher, values, range.first, range.second,
                                       depth)) {
                        matched = false;
                        break;
                    }
                }
                if (matched) return true;
            }
            return false;
        }
        // Finally, we get to the point of real value matching.
        // If the field matcher ends with ANY, then we have [start, end) range > 1.
        // In the following, we should return true, when ANY of the values matches.
        case FieldValueMatcher::ValueMatcherCase::kEqBool: {
            for (int i = start; i < end; i++) {
                if ((values[i].mValue.getType() == INT &&
                     (values[i].mValue.int_value != 0) == matcher.eq_bool()) ||
                    (values[i].mValue.getType() == LONG &&
                     (values[i].mValue.long_value != 0) == matcher.eq_bool())) {
                    return true;
                }
            }
            return false;
        }
        case FieldValueMatcher::ValueMatcherCase::kEqString: {
            for (int i = start; i < end; i++) {
                if (tryMatchString(uidMap, values[i], matcher.eq_string())) {
                    return true;
                }
            }
            return false;
        }
        case FieldValueMatcher::ValueMatcherCase::kNeqAnyString: {
            const auto& str_list = matcher.neq_any_string();
            for (int i = start; i < end; i++) {
                bool notEqAll = true;
                for (const auto& str : str_list.str_value()) {
                    if (tryMatchString(uidMap, values[i], str)) {
                        notEqAll = false;
                        break;
                    }
                }
                if (notEqAll) {
                    return true;
                }
            }
            return false;
        }
        case FieldValueMatcher::ValueMatcherCase::kEqAnyString: {
            const auto& str_list = matcher.eq_any_string();
            for (int i = start; i < end; i++) {
                for (const auto& str : str_list.str_value()) {
                    if (tryMatchString(uidMap, values[i], str)) {
                        return true;
                    }
                }
            }
            return false;
        }
        case FieldValueMatcher::ValueMatcherCase::kEqInt: {
            for (int i = start; i < end; i++) {
                if (values[i].mValue.getType() == INT &&
                    (matcher.eq_int() == values[i].mValue.int_value)) {
                    return true;
                }
                // eq_int covers both int and long.
                if (values[i].mValue.getType() == LONG &&
                    (matcher.eq_int() == values[i].mValue.long_value)) {
                    return true;
                }
            }
            return false;
        }
        case FieldValueMatcher::ValueMatcherCase::kLtInt: {
            for (int i = start; i < end; i++) {
                if (values[i].mValue.getType() == INT &&
                    (values[i].mValue.int_value < matcher.lt_int())) {
                    return true;
                }
                // lt_int covers both int and long.
                if (values[i].mValue.getType() == LONG &&
                    (values[i].mValue.long_value < matcher.lt_int())) {
                    return true;
                }
            }
            return false;
        }
        case FieldValueMatcher::ValueMatcherCase::kGtInt: {
            for (int i = start; i < end; i++) {
                if (values[i].mValue.getType() == INT &&
                    (values[i].mValue.int_value > matcher.gt_int())) {
                    return true;
                }
                // gt_int covers both int and long.
                if (values[i].mValue.getType() == LONG &&
                    (values[i].mValue.long_value > matcher.gt_int())) {
                    return true;
                }
            }
            return false;
        }
        case FieldValueMatcher::ValueMatcherCase::kLtFloat: {
            for (int i = start; i < end; i++) {
                if (values[i].mValue.getType() == FLOAT &&
                    (values[i].mValue.float_value < matcher.lt_float())) {
                    return true;
                }
            }
            return false;
        }
        case FieldValueMatcher::ValueMatcherCase::kGtFloat: {
            for (int i = start; i < end; i++) {
                if (values[i].mValue.getType() == FLOAT &&
                    (values[i].mValue.float_value > matcher.gt_float())) {
                    return true;
                }
            }
            return false;
        }
        case FieldValueMatcher::ValueMatcherCase::kLteInt: {
            for (int i = start; i < end; i++) {
                if (values[i].mValue.getType() == INT &&
                    (values[i].mValue.int_value <= matcher.lte_int())) {
                    return true;
                }
                // lte_int covers both int and long.
                if (values[i].mValue.getType() == LONG &&
                    (values[i].mValue.long_value <= matcher.lte_int())) {
                    return true;
                }
            }
            return false;
        }
        case FieldValueMatcher::ValueMatcherCase::kGteInt: {
            for (int i = start; i < end; i++) {
                if (values[i].mValue.getType() == INT &&
                    (values[i].mValue.int_value >= matcher.gte_int())) {
                    return true;
                }
                // gte_int covers both int and long.
                if (values[i].mValue.getType() == LONG &&
                    (values[i].mValue.long_value >= matcher.gte_int())) {
                    return true;
                }
            }
            return false;
        }
        default:
            return false;
    }
}

bool matchesSimple(const UidMap& uidMap, const SimpleAtomMatcher& simpleMatcher,
                   const LogEvent& event) {
    if (event.GetTagId() != simpleMatcher.atom_id()) {
        return false;
    }

    for (const auto& matcher : simpleMatcher.field_value_matcher()) {
        if (!matchesSimple(uidMap, matcher, event.getValues(), 0, event.getValues().size(), 0)) {
            return false;
        }
    }
    return true;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
