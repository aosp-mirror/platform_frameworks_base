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

#include <log/logprint.h>
#include <set>
#include <vector>
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"
#include "field_util.h"

namespace android {
namespace os {
namespace statsd {

// Returns the leaf node from the DimensionsValue proto. It assume that the input has only one
// leaf node at most.
const DimensionsValue* getSingleLeafValue(const DimensionsValue* value);
DimensionsValue getSingleLeafValue(const DimensionsValue& value);

// Appends the leaf node to the parent tree.
void appendLeafNodeToTree(const Field& field, const DimensionsValue& value, DimensionsValue* tree);

// Constructs the DimensionsValue protos from the FieldMatcher. Each DimensionsValue proto
// represents a tree. When the input proto has repeated fields and the input "dimensions" wants
// "ANY" locations, it will return multiple trees.
void findDimensionsValues(
       const FieldValueMap& fieldValueMap,
       const FieldMatcher& matcher,
       std::vector<DimensionsValue>* rootDimensionsValues);

// Utils to build FieldMatcher proto for simple one-depth atoms.
void buildSimpleAtomFieldMatcher(const int tagId, const int atomFieldNum, FieldMatcher* matcher);
void buildSimpleAtomFieldMatcher(const int tagId, FieldMatcher* matcher);

// Utils to build FieldMatcher proto for attribution nodes.
void buildAttributionUidFieldMatcher(const int tagId, const Position position,
                                     FieldMatcher* matcher);
void buildAttributionTagFieldMatcher(const int tagId, const Position position,
                                     FieldMatcher* matcher);
void buildAttributionFieldMatcher(const int tagId, const Position position,
                                  FieldMatcher* matcher);

// Utils to print pretty string for DimensionsValue proto.
std::string DimensionsValueToString(const DimensionsValue& value);
void DimensionsValueToString(const DimensionsValue& value, std::string *flattened);

bool IsSubDimension(const DimensionsValue& dimension, const DimensionsValue& sub);

// Helper function to get long value from the DimensionsValue proto.
long getLongFromDimenValue(const DimensionsValue& dimensionValue);

bool getSubDimension(const DimensionsValue& dimension, const FieldMatcher& matcher,
                    DimensionsValue* subDimension);
}  // namespace statsd
}  // namespace os
}  // namespace android
