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

void appendLeafNodeToTree(const Field& field,
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
    appendLeafNodeToTree(
        field.child(0), value,
        parentValue->mutable_value_tuple()->mutable_dimensions_value(idx));
}

void appendLeafNodeToTrees(const Field& field,
                           const DimensionsValue& node,
                           std::vector<DimensionsValue>* rootTrees) {
    if (rootTrees == nullptr) {
        return;
    }
    if (rootTrees->empty()) {
        DimensionsValue tree;
        appendLeafNodeToTree(field, node, &tree);
        rootTrees->push_back(tree);
    } else {
        for (size_t i = 0; i < rootTrees->size(); ++i) {
            appendLeafNodeToTree(field, node, &rootTrees->at(i));
        }
    }
}

namespace {

void findDimensionsValues(
       const FieldValueMap& fieldValueMap,
       const FieldMatcher& matcher,
       Field* rootField,
       Field* leafField,
       std::vector<DimensionsValue>* rootDimensionsValues);

void findNonRepeatedDimensionsValues(
       const FieldValueMap& fieldValueMap,
       const FieldMatcher& matcher,
       Field* rootField,
       Field* leafField,
       std::vector<DimensionsValue>* rootValues) {
    if (matcher.child_size() > 0) {
        Field* newLeafField = leafField->add_child();
        for (const auto& childMatcher : matcher.child()) {
          newLeafField->set_field(childMatcher.field());
          findDimensionsValues(fieldValueMap, childMatcher, rootField, newLeafField, rootValues);
        }
        leafField->clear_child();
    } else {
        auto ret = fieldValueMap.equal_range(*rootField);
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
        appendLeafNodeToTrees(*rootField, ret.first->second, rootValues);
    }
}

void findRepeatedDimensionsValues(const FieldValueMap& fieldValueMap,
                                  const FieldMatcher& matcher,
                                  Field* rootField,
                                  Field* leafField,
                                  std::vector<DimensionsValue>* rootValues) {
    if (matcher.position() == Position::FIRST) {
        leafField->set_position_index(0);
        findNonRepeatedDimensionsValues(fieldValueMap, matcher, rootField, leafField, rootValues);
        leafField->clear_position_index();
    } else {
        auto itLower = fieldValueMap.lower_bound(*rootField);
        if (itLower == fieldValueMap.end()) {
            return;
        }
        const int leafFieldNum = leafField->field();
        leafField->set_field(leafFieldNum + 1);
        auto itUpper = fieldValueMap.lower_bound(*rootField);
        // Resets the field number.
        leafField->set_field(leafFieldNum);

        switch (matcher.position()) {
             case Position::LAST:
                 {
                     itUpper--;
                     if (itUpper != fieldValueMap.end()) {
                         int last_index = getPositionByReferenceField(*rootField, itUpper->first);
                         if (last_index < 0) {
                            return;
                         }
                         leafField->set_position_index(last_index);
                         findNonRepeatedDimensionsValues(
                            fieldValueMap, matcher, rootField, leafField, rootValues);
                         leafField->clear_position_index();
                     }
                 }
                 break;
             case Position::ANY:
                 {
                    std::set<int> indexes;
                    for (auto it = itLower; it != itUpper; ++it) {
                        int index = getPositionByReferenceField(*rootField, it->first);
                        if (index >= 0) {
                            indexes.insert(index);
                        }
                    }
                    if (!indexes.empty()) {
                        std::vector<DimensionsValue> allValues;
                        for (const int index : indexes) {
                             leafField->set_position_index(index);
                             std::vector<DimensionsValue> newValues = *rootValues;
                             findNonRepeatedDimensionsValues(
                                fieldValueMap, matcher, rootField, leafField, &newValues);
                             allValues.insert(allValues.end(), newValues.begin(), newValues.end());
                             leafField->clear_position_index();
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
       Field* rootField,
       Field* leafField,
       std::vector<DimensionsValue>* rootDimensionsValues) {
    if (!matcher.has_position()) {
        findNonRepeatedDimensionsValues(fieldValueMap, matcher, rootField, leafField,
                                        rootDimensionsValues);
    } else {
        findRepeatedDimensionsValues(fieldValueMap, matcher, rootField, leafField,
                                     rootDimensionsValues);
    }
}

} // namespace

void findDimensionsValues(
       const FieldValueMap& fieldValueMap,
       const FieldMatcher& matcher,
       std::vector<DimensionsValue>* rootDimensionsValues) {
    Field rootField;
    buildSimpleAtomField(matcher.field(), &rootField);
    findDimensionsValues(fieldValueMap, matcher, &rootField, &rootField, rootDimensionsValues);
}

void buildSimpleAtomFieldMatcher(const int tagId, FieldMatcher* matcher) {
    matcher->set_field(tagId);
}

void buildSimpleAtomFieldMatcher(const int tagId, const int fieldNum, FieldMatcher* matcher) {
    matcher->set_field(tagId);
    matcher->add_child()->set_field(fieldNum);
}

constexpr int ATTRIBUTION_FIELD_NUM_IN_ATOM_PROTO = 1;
constexpr int UID_FIELD_NUM_IN_ATTRIBUTION_NODE_PROTO = 1;
constexpr int TAG_FIELD_NUM_IN_ATTRIBUTION_NODE_PROTO = 2;

void buildAttributionUidFieldMatcher(const int tagId, const Position position,
                                     FieldMatcher* matcher) {
    matcher->set_field(tagId);
    auto child = matcher->add_child();
    child->set_field(ATTRIBUTION_FIELD_NUM_IN_ATOM_PROTO);
    child->set_position(position);
    child->add_child()->set_field(UID_FIELD_NUM_IN_ATTRIBUTION_NODE_PROTO);
}

void buildAttributionTagFieldMatcher(const int tagId, const Position position,
                                     FieldMatcher* matcher) {
    matcher->set_field(tagId);
    FieldMatcher* child = matcher->add_child();
    child->set_field(ATTRIBUTION_FIELD_NUM_IN_ATOM_PROTO);
    child->set_position(position);
    child->add_child()->set_field(TAG_FIELD_NUM_IN_ATTRIBUTION_NODE_PROTO);
}

void buildAttributionFieldMatcher(const int tagId, const Position position, FieldMatcher* matcher) {
    matcher->set_field(tagId);
    FieldMatcher* child = matcher->add_child();
    child->set_field(ATTRIBUTION_FIELD_NUM_IN_ATOM_PROTO);
    child->set_position(position);
    child->add_child()->set_field(UID_FIELD_NUM_IN_ATTRIBUTION_NODE_PROTO);
    child->add_child()->set_field(TAG_FIELD_NUM_IN_ATTRIBUTION_NODE_PROTO);
}

void DimensionsValueToString(const DimensionsValue& value, std::string *flattened) {
    if (!value.has_field()) {
        return;
    }
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

bool getSubDimension(const DimensionsValue& dimension, const FieldMatcher& matcher,
                     DimensionsValue* subDimension) {
    if (!matcher.has_field()) {
        return false;
    }
    if (matcher.field() != dimension.field()) {
        return false;
    }
    if (matcher.child_size() <= 0) {
        if (dimension.value_case() == DimensionsValue::ValueCase::kValueTuple ||
            dimension.value_case() == DimensionsValue::ValueCase::VALUE_NOT_SET) {
            return false;
        }
        *subDimension = dimension;
        return true;
    } else {
        if (dimension.value_case() != DimensionsValue::ValueCase::kValueTuple) {
            return false;
        }
        bool found_value = true;
        auto value_tuple = dimension.value_tuple();
        subDimension->set_field(dimension.field());
        for (int i = 0; found_value && i < matcher.child_size(); ++i) {
            int j = 0;
            for (; j < value_tuple.dimensions_value_size(); ++j) {
                if (value_tuple.dimensions_value(j).field() == matcher.child(i).field()) {
                    break;
                }
            }
            if (j < value_tuple.dimensions_value_size()) {
                found_value &= getSubDimension(value_tuple.dimensions_value(j), matcher.child(i),
                    subDimension->mutable_value_tuple()->add_dimensions_value());
            } else {
                found_value = false;
            }
        }
        return found_value;
    }
}

}  // namespace statsd
}  // namespace os
}  // namespace android
