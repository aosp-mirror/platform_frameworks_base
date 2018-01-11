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

#pragma once

#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_internal.pb.h"
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"

#include <unordered_map>

namespace android {
namespace os {
namespace statsd {

// Function to sort the Field protos.
bool CompareField(const Field& a, const Field& b);
struct FieldCmp {
    bool operator()(const Field& a, const Field& b) const {
        return CompareField(a, b);
    }
};

// Flattened dimensions value map. To save space, usually the key contains the tree structure info
// and value field is only leaf node.
typedef std::map<Field, DimensionsValue, FieldCmp> FieldValueMap;

// Util function to print the Field proto.
std::string FieldToString(const Field& field);

// Util function to find the leaf node from the input Field proto and set it in the corresponding
// value proto.
bool setFieldInLeafValueProto(const Field &field, DimensionsValue* leafValue);

// Returns the leaf node from the Field proto. It assume that the input has only one
// leaf node at most.
const Field* getSingleLeaf(const Field* field);
Field* getSingleLeaf(Field* field);

// Append a node to the current leaf. It assumes that the input "parent" has one leaf node at most.
void appendLeaf(Field *parent, int node_field_num);
void appendLeaf(Field *parent, int node_field_num, int position);

// Given the field sorting logic, this function is to increase the "field" at the leaf node.
void getNextField(Field* field);

// Increase the position index for the node. If the "position_index" is not set, set it as 0.
void increasePosition(Field *field);

// Finds the leaf node and set the index there.
void setPositionForLeaf(Field *field, int index);

// Returns true if the matcher has specified at least one leaf node.
bool hasLeafNode(const FieldMatcher& matcher);

// The two input Field proto are describing the same tree structure. Both contain one leaf node at
// most. This is find the position index info for the leaf node at "reference" stored in the
// "field_with_index" tree.
int getPositionByReferenceField(const Field& reference, const Field& field_with_index);

// Utils to build the Field proto for simple atom fields.
Field buildAtomField(const int tagId, const Field &atomField);
Field buildSimpleAtomField(const int tagId, const int atomFieldNum);
Field buildSimpleAtomField(const int tagId);

// Find out all the fields specified by the matcher.
void findFields(
       const FieldValueMap& fieldValueMap,
       const FieldMatcher& matcher,
       std::vector<Field>* rootFields);

// Filter out the fields not in the field matcher.
void filterFields(const FieldMatcher& matcher, FieldValueMap* fieldValueMap);

// Returns if the field is attribution node uid field.
bool IsAttributionUidField(const Field& field);

}  // namespace statsd
}  // namespace os
}  // namespace android
