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

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_internal.pb.h"
#include "dimension.h"


namespace android {
namespace os {
namespace statsd {

const DimensionsValue* getSingleLeafValue(const DimensionsValue* value) {
    if (value->value_case() == DimensionsValue::ValueCase::kValueTuple) {
        return getSingleLeafValue(&value->value_tuple().dimensions_value(0));
    } else {
        return value;
    }
}

DimensionsValue getSingleLeafValue(const DimensionsValue& value) {
    const DimensionsValue* leafValue = getSingleLeafValue(&value);
    return *leafValue;
}

void appendLeafNodeToParent(const Field& field,
                            const DimensionsValue& value,
                            DimensionsValue* parentValue) {
    if (field.child_size() <= 0) {
        *parentValue = value;
        parentValue->set_field(field.field());
        return;
    }
    parentValue->set_field(field.field());
    int idx = -1;
    for (int i = 0; i < parentValue->mutable_value_tuple()->dimensions_value_size(); ++i) {
        if (parentValue->mutable_value_tuple()->dimensions_value(i).field() ==
                field.child(0).field()) {
            idx = i;
        }
    }
    if (idx < 0) {
        parentValue->mutable_value_tuple()->add_dimensions_value();
        idx = parentValue->mutable_value_tuple()->dimensions_value_size() - 1;
    }
    appendLeafNodeToParent(
        field.child(0), value,
        parentValue->mutable_value_tuple()->mutable_dimensions_value(idx));
}

void addNodeToRootDimensionsValues(const Field& field,
                                   const DimensionsValue& node,
                                   std::vector<DimensionsValue>* rootValues) {
    if (rootValues == nullptr) {
        return;
    }
    if (rootValues->empty()) {
        DimensionsValue rootValue;
        appendLeafNodeToParent(field, node, &rootValue);
        rootValues->push_back(rootValue);
    } else {
        for (size_t i = 0; i < rootValues->size(); ++i) {
            appendLeafNodeToParent(field, node, &rootValues->at(i));
        }
    }
}

namespace {

void findDimensionsValues(
       const FieldValueMap& fieldValueMap,
       const FieldMatcher& matcher,
       const Field& field,
       std::vector<DimensionsValue>* rootDimensionsValues);

void findNonRepeatedDimensionsValues(
       const FieldValueMap& fieldValueMap,
       const FieldMatcher& matcher,
       const Field& field,
       std::vector<DimensionsValue>* rootValues) {
    if (matcher.child_size() > 0) {
        for (const auto& childMatcher : matcher.child()) {
          Field childField = field;
          appendLeaf(&childField, childMatcher.field());
          findDimensionsValues(fieldValueMap, childMatcher, childField, rootValues);
        }
    } else {
        auto ret = fieldValueMap.equal_range(field);
        int found = 0;
        for (auto it = ret.first; it != ret.second; ++it) {
            found++;
        }
        // Not found.
        if (found <= 0) {
            return;
        }
        if (found > 1) {
            ALOGE("Found multiple values for optional field.");
            return;
        }
        addNodeToRootDimensionsValues(field, ret.first->second, rootValues);
    }
}

void findRepeatedDimensionsValues(const FieldValueMap& fieldValueMap,
                                  const FieldMatcher& matcher,
                                  const Field& field,
                                  std::vector<DimensionsValue>* rootValues) {
    if (matcher.position() == Position::FIRST) {
        Field first_field = field;
        setPositionForLeaf(&first_field, 0);
        findNonRepeatedDimensionsValues(fieldValueMap, matcher, first_field, rootValues);
    } else {
        auto itLower = fieldValueMap.lower_bound(field);
        if (itLower == fieldValueMap.end()) {
            return;
        }
        Field next_field = field;
        getNextField(&next_field);
        auto itUpper = fieldValueMap.lower_bound(next_field);

        switch (matcher.position()) {
             case Position::LAST:
                 {
                     itUpper--;
                     if (itUpper != fieldValueMap.end()) {
                         Field last_field = field;
                         int last_index = getPositionByReferenceField(field, itUpper->first);
                         if (last_index < 0) {
                            return;
                         }
                         setPositionForLeaf(&last_field, last_index);
                         findNonRepeatedDimensionsValues(
                            fieldValueMap, matcher, last_field, rootValues);
                     }
                 }
                 break;
             case Position::ANY:
                 {
                    std::set<int> indexes;
                    for (auto it = itLower; it != itUpper; ++it) {
                        int index = getPositionByReferenceField(field, it->first);
                        if (index >= 0) {
                            indexes.insert(index);
                        }
                    }
                    if (!indexes.empty()) {
                        Field any_field = field;
                        std::vector<DimensionsValue> allValues;
                        for (const int index : indexes) {
                             setPositionForLeaf(&any_field, index);
                             std::vector<DimensionsValue> newValues = *rootValues;
                             findNonRepeatedDimensionsValues(
                                fieldValueMap, matcher, any_field, &newValues);
                             allValues.insert(allValues.end(), newValues.begin(), newValues.end());
                        }
                        rootValues->clear();
                        rootValues->insert(rootValues->end(), allValues.begin(), allValues.end());
                    }
                 }
                 break;
             default:
                break;
         }
    }
}

void findDimensionsValues(
       const FieldValueMap& fieldValueMap,
       const FieldMatcher& matcher,
       const Field& field,
       std::vector<DimensionsValue>* rootDimensionsValues) {
    if (!matcher.has_position()) {
        findNonRepeatedDimensionsValues(fieldValueMap, matcher, field, rootDimensionsValues);
    } else {
        findRepeatedDimensionsValues(fieldValueMap, matcher, field, rootDimensionsValues);
    }
}

} // namespace

void findDimensionsValues(
       const FieldValueMap& fieldValueMap,
       const FieldMatcher& matcher,
       std::vector<DimensionsValue>* rootDimensionsValues) {
    findDimensionsValues(fieldValueMap, matcher,
                    buildSimpleAtomField(matcher.field()), rootDimensionsValues);
}

FieldMatcher buildSimpleAtomFieldMatcher(const int tagId) {
    FieldMatcher matcher;
    matcher.set_field(tagId);
    return matcher;
}

FieldMatcher buildSimpleAtomFieldMatcher(const int tagId, const int atomFieldNum) {
    FieldMatcher matcher;
    matcher.set_field(tagId);
    matcher.add_child()->set_field(atomFieldNum);
    return matcher;
}

constexpr int ATTRIBUTION_FIELD_NUM_IN_ATOM_PROTO = 1;
constexpr int UID_FIELD_NUM_IN_ATTRIBUTION_NODE_PROTO = 1;
constexpr int TAG_FIELD_NUM_IN_ATTRIBUTION_NODE_PROTO = 2;

FieldMatcher buildAttributionUidFieldMatcher(const int tagId, const Position position) {
    FieldMatcher matcher;
    matcher.set_field(tagId);
    auto child = matcher.add_child();
    child->set_field(ATTRIBUTION_FIELD_NUM_IN_ATOM_PROTO);
    child->set_position(position);
    child->add_child()->set_field(UID_FIELD_NUM_IN_ATTRIBUTION_NODE_PROTO);
    return matcher;
}

FieldMatcher buildAttributionTagFieldMatcher(const int tagId, const Position position) {
    FieldMatcher matcher;
    matcher.set_field(tagId);
    FieldMatcher* child = matcher.add_child();
    child->set_field(ATTRIBUTION_FIELD_NUM_IN_ATOM_PROTO);
    child->set_position(position);
    child->add_child()->set_field(TAG_FIELD_NUM_IN_ATTRIBUTION_NODE_PROTO);
    return matcher;
}

FieldMatcher buildAttributionFieldMatcher(const int tagId, const Position position) {
    FieldMatcher matcher;
    matcher.set_field(tagId);
    FieldMatcher* child = matcher.add_child();
    child->set_field(ATTRIBUTION_FIELD_NUM_IN_ATOM_PROTO);
    child->set_position(position);
    child->add_child()->set_field(UID_FIELD_NUM_IN_ATTRIBUTION_NODE_PROTO);
    child->add_child()->set_field(TAG_FIELD_NUM_IN_ATTRIBUTION_NODE_PROTO);
    return matcher;
}

void DimensionsValueToString(const DimensionsValue& value, std::string *flattened) {
    *flattened += std::to_string(value.field());
    *flattened += ":";
    switch (value.value_case()) {
        case DimensionsValue::ValueCase::kValueStr:
            *flattened += value.value_str();
            break;
        case DimensionsValue::ValueCase::kValueInt:
            *flattened += std::to_string(value.value_int());
            break;
        case DimensionsValue::ValueCase::kValueLong:
            *flattened += std::to_string(value.value_long());
            break;
        case DimensionsValue::ValueCase::kValueBool:
            *flattened += std::to_string(value.value_bool());
            break;
        case DimensionsValue::ValueCase::kValueFloat:
            *flattened += std::to_string(value.value_float());
            break;
        case DimensionsValue::ValueCase::kValueTuple:
            {
                *flattened += "{";
                for (int i = 0; i < value.value_tuple().dimensions_value_size(); ++i) {
                    DimensionsValueToString(value.value_tuple().dimensions_value(i), flattened);
                    *flattened += "|";
                }
                *flattened += "}";
            }
            break;
        case DimensionsValue::ValueCase::VALUE_NOT_SET:
            break;
    }
}

void getDimensionsValueLeafNodes(
    const DimensionsValue& value, std::vector<DimensionsValue> *leafNodes) {
    switch (value.value_case()) {
        case DimensionsValue::ValueCase::kValueStr:
        case DimensionsValue::ValueCase::kValueInt:
        case DimensionsValue::ValueCase::kValueLong:
        case DimensionsValue::ValueCase::kValueBool:
        case DimensionsValue::ValueCase::kValueFloat:
            leafNodes->push_back(value);
            break;
        case DimensionsValue::ValueCase::kValueTuple:
            for (int i = 0; i < value.value_tuple().dimensions_value_size(); ++i) {
                getDimensionsValueLeafNodes(value.value_tuple().dimensions_value(i), leafNodes);
            }
            break;
        case DimensionsValue::ValueCase::VALUE_NOT_SET:
            break;
        default:
            break;
    }
}

std::string DimensionsValueToString(const DimensionsValue& value) {
    std::string flatten;
    DimensionsValueToString(value, &flatten);
    return flatten;
}

bool IsSubDimension(const DimensionsValue& dimension, const DimensionsValue& sub) {
    if (dimension.field() != sub.field()) {
        return false;
    }
    if (dimension.value_case() != sub.value_case()) {
        return false;
    }
    switch (dimension.value_case()) {
        case DimensionsValue::ValueCase::kValueStr:
            return dimension.value_str() == sub.value_str();
        case DimensionsValue::ValueCase::kValueInt:
            return dimension.value_int() == sub.value_int();
        case DimensionsValue::ValueCase::kValueLong:
            return dimension.value_long() == sub.value_long();
        case DimensionsValue::ValueCase::kValueBool:
            return dimension.value_bool() == sub.value_bool();
        case DimensionsValue::ValueCase::kValueFloat:
            return dimension.value_float() == sub.value_float();
        case DimensionsValue::ValueCase::kValueTuple: {
            if (dimension.value_tuple().dimensions_value_size() <
                sub.value_tuple().dimensions_value_size()) {
                return false;
            }
            bool allSub = true;
            for (int i = 0; allSub && i < sub.value_tuple().dimensions_value_size(); ++i) {
                bool isSub = false;
                for (int j = 0; !isSub &&
                        j < dimension.value_tuple().dimensions_value_size(); ++j) {
                    isSub |= IsSubDimension(dimension.value_tuple().dimensions_value(j),
                                            sub.value_tuple().dimensions_value(i));
                }
                allSub &= isSub;
            }
            return allSub;
        }
        break;
        case DimensionsValue::ValueCase::VALUE_NOT_SET:
            return false;
        default:
            return false;
    }
}

long getLongFromDimenValue(const DimensionsValue& dimensionValue) {
    switch (dimensionValue.value_case()) {
        case DimensionsValue::ValueCase::kValueInt:
            return dimensionValue.value_int();
        case DimensionsValue::ValueCase::kValueLong:
            return dimensionValue.value_long();
        case DimensionsValue::ValueCase::kValueBool:
            return dimensionValue.value_bool() ? 1 : 0;
        case DimensionsValue::ValueCase::kValueFloat:
            return (int64_t)dimensionValue.value_float();
        case DimensionsValue::ValueCase::kValueTuple:
        case DimensionsValue::ValueCase::kValueStr:
        case DimensionsValue::ValueCase::VALUE_NOT_SET:
            return 0;
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
