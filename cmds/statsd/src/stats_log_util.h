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

#include <android/util/ProtoOutputStream.h>
#include "frameworks/base/cmds/statsd/src/stats_log.pb.h"
#include "field_util.h"

namespace android {
namespace os {
namespace statsd {

// Helper function to write DimensionsValue proto to ProtoOutputStream.
void writeDimensionsValueProtoToStream(
    const DimensionsValue& fieldValue, util::ProtoOutputStream* protoOutput);

// Helper function to write Field proto to ProtoOutputStream.
void writeFieldProtoToStream(
    const Field& field, util::ProtoOutputStream* protoOutput);

// Helper function to construct the field value tree and write to ProtoOutputStream
void writeFieldValueTreeToStream(const FieldValueMap &fieldValueMap,
    util::ProtoOutputStream* protoOutput);

}  // namespace statsd
}  // namespace os
}  // namespace android