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
#include "field_util.h"

#include <set>
#include <vector>

namespace android {
namespace os {
namespace statsd {

// This function is to compare two Field trees where each node has at most one child.
bool CompareField(const Field& a, const Field& b) {
    if (a.field() < b.field()) {
        return true;
    }
    if (a.field() > b.field()) {
        return false;
    }
    if (a.position_index() < b.position_index()) {
        return true;
    }
    if (a.position_index() > b.position_index()) {
        return false;
    }
    if (a.child_size() < b.child_size()) {
        return true;
    }
    if (a.child_size() > b.child_size()) {
        return false;
    }
    if (a.child_size() == 0 && b.child_size() == 0) {
       return false;
    }
    return CompareField(a.child(0), b.child(0));
}

const Field* getSingleLeaf(const Field* field) {
    if (field->child_size() <= 0) {
        return field;
    } else {
        return getSingleLeaf(&field->child(0));
    }
}

Field* getSingleLeaf(Field* field) {
    if (field->child_size() <= 0) {
        return field;
    } else {
        return getSingleLeaf(field->mutable_child(0));
    }
}

void FieldToString(const Field& field, std::string *flattened) {
    *flattened += std::to_string(field.field());
    if (field.has_position_index()) {
        *flattened += "[";
        *flattened += std::to_string(field.position_index());
        *flattened += "]";
    }
    if (field.child_size() <= 0) {
        return;
    }
    *flattened += ".";
    *flattened += "{";
    for (int i = 0 ; i < field.child_size(); ++i) {
        *flattened += FieldToString(field.child(i));
    }
    *flattened += "},";
}

std::string FieldToString(const Field& field) {
    std::string flatten;
    FieldToString(field, &flatten);
    return flatten;
}

bool setFieldInLeafValueProto(const Field &field, DimensionsValue* leafValue) {
    if (field.child_size() <= 0) {
        leafValue->set_field(field.field());
        return true;
    } else if (field.child_size() == 1)  {
        return setFieldInLeafValueProto(field.child(0), leafValue);
    } else {
        ALOGE("Not able to set the 'field' in leaf value for multiple children.");
        return false;
    }
}

Field buildAtomField(const int tagId, const Field &atomField) {
    Field field;
    *field.add_child() = atomField;
    field.set_field(tagId);
    return field;
}

Field buildSimpleAtomField(const int tagId, const int atomFieldNum) {
    Field field;
    field.set_field(tagId);
    field.add_child()->set_field(atomFieldNum);
    return field;
}

Field buildSimpleAtomField(const int tagId) {
    Field field;
    field.set_field(tagId);
    return field;
}

void appendLeaf(Field *parent, int node_field_num) {
    if (!parent->has_field()) {
        parent->set_field(node_field_num);
    } else if (parent->child_size() <= 0) {
        parent->add_child()->set_field(node_field_num);
    } else {
        appendLeaf(parent->mutable_child(0), node_field_num);
    }
}

void appendLeaf(Field *parent, int node_field_num, int position) {
    if (!parent->has_field()) {
        parent->set_field(node_field_num);
        parent->set_position_index(position);
    } else if (parent->child_size() <= 0) {
        auto child = parent->add_child();
        child->set_field(node_field_num);
        child->set_position_index(position);
    } else {
        appendLeaf(parent->mutable_child(0), node_field_num, position);
    }
}


void getNextField(Field* field) {
    if (field->child_size() <= 0) {
        field->set_field(field->field() + 1);
        return;
    }
    if (field->child_size() != 1) {
        return;
    }
    getNextField(field->mutable_child(0));
}

void increasePosition(Field *field) {
    if (!field->has_position_index()) {
        field->set_position_index(0);
    } else {
        field->set_position_index(field->position_index() + 1);
    }
}

int getPositionByReferenceField(const Field& ref, const Field& field_with_index) {
    if (ref.child_size() <= 0) {
        return field_with_index.position_index();
    }
    if (ref.child_size() != 1 ||
        field_with_index.child_size() != 1) {
        return -1;
    }
    return getPositionByReferenceField(ref.child(0), field_with_index.child(0));
}

void setPositionForLeaf(Field *field, int index) {
    if (field->child_size() <= 0) {
        field->set_position_index(index);
    } else {
        setPositionForLeaf(field->mutable_child(0), index);
    }
}

namespace {
void findFields(
       const FieldValueMap& fieldValueMap,
       const FieldMatcher& matcher,
       const Field& field,
       std::vector<Field>* rootFields);

void findNonRepeatedFields(
       const FieldValueMap& fieldValueMap,
       const FieldMatcher& matcher,
       const Field& field,
       std::vector<Field>* rootFields) {
    if (matcher.child_size() > 0) {
        for (const auto& childMatcher : matcher.child()) {
          Field childField = field;
          appendLeaf(&childField, childMatcher.field());
          findFields(fieldValueMap, childMatcher, childField, rootFields);
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
        rootFields->push_back(ret.first->first);
    }
}

void findRepeatedFields(const FieldValueMap& fieldValueMap, const FieldMatcher& matcher,
                        const Field& field, std::vector<Field>* rootFields) {
    if (matcher.position() == Position::FIRST) {
        Field first_field = field;
        setPositionForLeaf(&first_field, 0);
        findNonRepeatedFields(fieldValueMap, matcher, first_field, rootFields);
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
                         findNonRepeatedFields(
                            fieldValueMap, matcher, last_field, rootFields);
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
                        for (const int index : indexes) {
                             setPositionForLeaf(&any_field, index);
                             findNonRepeatedFields(
                                fieldValueMap, matcher, any_field, rootFields);
                        }
                    }
                 }
                 break;
             default:
                break;
         }
    }
}

void findFields(
       const FieldValueMap& fieldValueMap,
       const FieldMatcher& matcher,
       const Field& field,
       std::vector<Field>* rootFields) {
    if (!matcher.has_position()) {
        findNonRepeatedFields(fieldValueMap, matcher, field, rootFields);
    } else {
        findRepeatedFields(fieldValueMap, matcher, field, rootFields);
    }
}

}  // namespace

void findFields(
       const FieldValueMap& fieldValueMap,
       const FieldMatcher& matcher,
       std::vector<Field>* rootFields) {
    return findFields(fieldValueMap, matcher, buildSimpleAtomField(matcher.field()), rootFields);
}

void filterFields(const FieldMatcher& matcher, FieldValueMap* fieldValueMap) {
    std::vector<Field> rootFields;
    findFields(*fieldValueMap, matcher, &rootFields);
    std::set<Field, FieldCmp> rootFieldSet(rootFields.begin(), rootFields.end());
    auto it = fieldValueMap->begin();
    while (it != fieldValueMap->end()) {
        if (rootFieldSet.find(it->first) == rootFieldSet.end()) {
            it = fieldValueMap->erase(it);
        } else {
            it++;
        }
    }
}

bool hasLeafNode(const FieldMatcher& matcher) {
    if (!matcher.has_field()) {
        return false;
    }
    for (int i = 0; i < matcher.child_size(); ++i) {
        if (hasLeafNode(matcher.child(i))) {
            return true;
        }
    }
    return true;
}

bool IsAttributionUidField(const Field& field) {
    return field.child_size() == 1 && field.child(0).field() == 1
        && field.child(0).child_size() == 1 && field.child(0).child(0).field() == 1;
}

}  // namespace statsd
}  // namespace os
}  // namespace android
