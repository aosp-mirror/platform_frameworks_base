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

#include "stats_log_util.h"

#include <set>
#include <stack>
#include <utils/Log.h>

using android::util::FIELD_COUNT_REPEATED;
using android::util::FIELD_TYPE_BOOL;
using android::util::FIELD_TYPE_FLOAT;
using android::util::FIELD_TYPE_INT32;
using android::util::FIELD_TYPE_INT64;
using android::util::FIELD_TYPE_MESSAGE;
using android::util::FIELD_TYPE_STRING;
using android::util::ProtoOutputStream;

namespace android {
namespace os {
namespace statsd {

// for DimensionsValue Proto
const int DIMENSIONS_VALUE_FIELD = 1;
const int DIMENSIONS_VALUE_VALUE_STR = 2;
const int DIMENSIONS_VALUE_VALUE_INT = 3;
const int DIMENSIONS_VALUE_VALUE_LONG = 4;
const int DIMENSIONS_VALUE_VALUE_BOOL = 5;
const int DIMENSIONS_VALUE_VALUE_FLOAT = 6;
const int DIMENSIONS_VALUE_VALUE_TUPLE = 7;

// for MessageValue Proto
const int FIELD_ID_FIELD_VALUE_IN_MESSAGE_VALUE_PROTO = 1;

// for PulledAtomStats proto
const int FIELD_ID_PULLED_ATOM_STATS = 10;
const int FIELD_ID_PULL_ATOM_ID = 1;
const int FIELD_ID_TOTAL_PULL = 2;
const int FIELD_ID_TOTAL_PULL_FROM_CACHE = 3;
const int FIELD_ID_MIN_PULL_INTERVAL_SEC = 4;

void writeDimensionsValueProtoToStream(const DimensionsValue& dimensionsValue,
                                       ProtoOutputStream* protoOutput) {
    if (!dimensionsValue.has_field()) {
        return;
    }
    protoOutput->write(FIELD_TYPE_INT32 | DIMENSIONS_VALUE_FIELD, dimensionsValue.field());
    switch (dimensionsValue.value_case()) {
        case DimensionsValue::ValueCase::kValueStr:
            protoOutput->write(FIELD_TYPE_STRING | DIMENSIONS_VALUE_VALUE_STR,
                dimensionsValue.value_str());
            break;
        case DimensionsValue::ValueCase::kValueInt:
            protoOutput->write(FIELD_TYPE_INT32 | DIMENSIONS_VALUE_VALUE_INT,
                dimensionsValue.value_int());
            break;
        case DimensionsValue::ValueCase::kValueLong:
            protoOutput->write(FIELD_TYPE_INT64 | DIMENSIONS_VALUE_VALUE_LONG,
                dimensionsValue.value_long());
            break;
        case DimensionsValue::ValueCase::kValueBool:
            protoOutput->write(FIELD_TYPE_BOOL | DIMENSIONS_VALUE_VALUE_BOOL,
                dimensionsValue.value_bool());
            break;
        case DimensionsValue::ValueCase::kValueFloat:
            protoOutput->write(FIELD_TYPE_FLOAT | DIMENSIONS_VALUE_VALUE_FLOAT,
                dimensionsValue.value_float());
            break;
        case DimensionsValue::ValueCase::kValueTuple:
            {
                long long tupleToken = protoOutput->start(
                    FIELD_TYPE_MESSAGE | DIMENSIONS_VALUE_VALUE_TUPLE);
                for (int i = 0; i < dimensionsValue.value_tuple().dimensions_value_size(); ++i) {
                    long long token = protoOutput->start(
                        FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED
                        | FIELD_ID_FIELD_VALUE_IN_MESSAGE_VALUE_PROTO);
                    writeDimensionsValueProtoToStream(
                        dimensionsValue.value_tuple().dimensions_value(i), protoOutput);
                    protoOutput->end(token);
                }
                protoOutput->end(tupleToken);
            }
            break;
        default:
            break;
    }
}

// for Field Proto
const int FIELD_FIELD = 1;
const int FIELD_POSITION_INDEX = 2;
const int FIELD_CHILD = 3;

void writeFieldProtoToStream(
    const Field& field, util::ProtoOutputStream* protoOutput) {
    if (!field.has_field()) {
        return;
    }
    protoOutput->write(FIELD_TYPE_INT32 | FIELD_FIELD, field.field());
    if (field.has_position_index()) {
      protoOutput->write(FIELD_TYPE_INT32 | FIELD_POSITION_INDEX, field.position_index());
    }
    for (int i = 0; i < field.child_size(); ++i) {
        long long childToken = protoOutput->start(
            FIELD_TYPE_MESSAGE| FIELD_COUNT_REPEATED | FIELD_CHILD);
        writeFieldProtoToStream(field.child(i), protoOutput);
        protoOutput->end(childToken);
    }
}

namespace {

void addOrUpdateChildrenMap(
    const Field& root,
    const Field& node,
    std::map<Field, std::set<Field, FieldCmp>, FieldCmp> *childrenMap) {
    Field parentNode = root;
    if (node.has_position_index()) {
        appendLeaf(&parentNode, node.field(), node.position_index());
    } else {
        appendLeaf(&parentNode, node.field());
    }
    if (childrenMap->find(parentNode) == childrenMap->end()) {
        childrenMap->insert(std::make_pair(parentNode, std::set<Field, FieldCmp>{}));
    }
    auto it = childrenMap->find(parentNode);
    for (int i = 0; i < node.child_size(); ++i) {
        auto child = node.child(i);
        Field childNode = parentNode;
        if (child.has_position_index()) {
            appendLeaf(&childNode, child.field(), child.position_index());
        } else {
            appendLeaf(&childNode, child.field());
        }
        it->second.insert(childNode);
        addOrUpdateChildrenMap(parentNode, child, childrenMap);
    }
}

void addOrUpdateChildrenMap(
    const Field& field,
    std::map<Field, std::set<Field, FieldCmp>, FieldCmp> *childrenMap) {
    Field root;
    addOrUpdateChildrenMap(root, field, childrenMap);
}

} // namespace

void writeFieldValueTreeToStream(const FieldValueMap &fieldValueMap,
                                 util::ProtoOutputStream* protoOutput) {
    std::map<Field, std::set<Field, FieldCmp>, FieldCmp> childrenMap;
    // Rebuild the field tree.
    for (auto it = fieldValueMap.begin(); it != fieldValueMap.end(); ++it) {
        addOrUpdateChildrenMap(it->first, &childrenMap);
    }
    std::stack<std::pair<long long, Field>> tokenStack;
    // Iterate over the node tree to fill the Atom proto.
    for (auto it = childrenMap.begin(); it != childrenMap.end(); ++it) {
        const Field* nodeLeaf = getSingleLeaf(&it->first);
        const int fieldNum = nodeLeaf->field();
        while (!tokenStack.empty()) {
            auto currentMsgNode = tokenStack.top().second;
            auto currentMsgNodeChildrenIt = childrenMap.find(currentMsgNode);
            if (currentMsgNodeChildrenIt->second.find(it->first) ==
                currentMsgNodeChildrenIt->second.end()) {
                protoOutput->end(tokenStack.top().first);
                tokenStack.pop();
            } else {
                break;
            }
        }
        if (it->second.size() == 0) {
            auto itValue = fieldValueMap.find(it->first);
            if (itValue != fieldValueMap.end()) {
                const DimensionsValue& value = itValue->second;
                switch (value.value_case()) {
                        case DimensionsValue::ValueCase::kValueStr:
                            protoOutput->write(FIELD_TYPE_STRING | fieldNum,
                                value.value_str());
                            break;
                        case DimensionsValue::ValueCase::kValueInt:
                            protoOutput->write(FIELD_TYPE_INT32 | fieldNum,
                                value.value_int());
                            break;
                        case DimensionsValue::ValueCase::kValueLong:
                            protoOutput->write(FIELD_TYPE_INT64 | fieldNum,
                                value.value_long());
                            break;
                        case DimensionsValue::ValueCase::kValueBool:
                            protoOutput->write(FIELD_TYPE_BOOL | fieldNum,
                                value.value_bool());
                            break;
                        case DimensionsValue::ValueCase::kValueFloat:
                            protoOutput->write(FIELD_TYPE_FLOAT | fieldNum,
                                value.value_float());
                            break;
                        // This would not happen as the node has no child.
                        case DimensionsValue::ValueCase::kValueTuple:
                            break;
                        default:
                            break;
                }
            } else {
                ALOGE("Leaf node value not found. This should never happen.");
            }
        } else {
            long long token;
            if (nodeLeaf->has_position_index()) {
                token = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_COUNT_REPEATED | fieldNum);
            } else {
                token = protoOutput->start(FIELD_TYPE_MESSAGE | fieldNum);
            }
            tokenStack.push(std::make_pair(token, it->first));
        }
    }

    while (!tokenStack.empty()) {
        protoOutput->end(tokenStack.top().first);
        tokenStack.pop();
    }
}

int64_t TimeUnitToBucketSizeInMillis(TimeUnit unit) {
    switch (unit) {
        case ONE_MINUTE:
            return 60 * 1000LL;
        case FIVE_MINUTES:
            return 5 * 60 * 1000LL;
        case TEN_MINUTES:
            return 10 * 60 * 1000LL;
        case THIRTY_MINUTES:
            return 30 * 60 * 1000LL;
        case ONE_HOUR:
            return 60 * 60 * 1000LL;
        case THREE_HOURS:
            return 3 * 60 * 60 * 1000LL;
        case SIX_HOURS:
            return 6 * 60 * 60 * 1000LL;
        case TWELVE_HOURS:
            return 12 * 60 * 60 * 1000LL;
        case ONE_DAY:
            return 24 * 60 * 60 * 1000LL;
        case CTS:
            return 1000;
        case TIME_UNIT_UNSPECIFIED:
        default:
            return -1;
    }
}

void writePullerStatsToStream(const std::pair<int, StatsdStats::PulledAtomStats>& pair,
                              util::ProtoOutputStream* protoOutput) {
    long long token = protoOutput->start(FIELD_TYPE_MESSAGE | FIELD_ID_PULLED_ATOM_STATS |
                                         FIELD_COUNT_REPEATED);
    protoOutput->write(FIELD_TYPE_INT32 | FIELD_ID_PULL_ATOM_ID, (int32_t)pair.first);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_TOTAL_PULL, (long long)pair.second.totalPull);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_TOTAL_PULL_FROM_CACHE,
                       (long long)pair.second.totalPullFromCache);
    protoOutput->write(FIELD_TYPE_INT64 | FIELD_ID_MIN_PULL_INTERVAL_SEC,
                       (long long)pair.second.minPullIntervalSec);
    protoOutput->end(token);
}

}  // namespace statsd
}  // namespace os
}  // namespace android