/*
 * Copyright (C) 2016 The Android Open Source Project
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

#ifndef AAPT_PROTO_PROTOHELPERS_H
#define AAPT_PROTO_PROTOHELPERS_H

#include "androidfw/ResourceTypes.h"

#include "ConfigDescription.h"
#include "ResourceTable.h"
#include "Source.h"
#include "StringPool.h"
#include "Format.pb.h"

namespace aapt {

void SerializeStringPoolToPb(const StringPool& pool,
                             pb::StringPool* out_pb_pool);

void SerializeSourceToPb(const Source& source, StringPool* src_pool,
                         pb::Source* out_pb_source);

void DeserializeSourceFromPb(const pb::Source& pb_source,
                             const android::ResStringPool& src_pool,
                             Source* out_source);

pb::SymbolStatus_Visibility SerializeVisibilityToPb(SymbolState state);

SymbolState DeserializeVisibilityFromPb(
    pb::SymbolStatus_Visibility pb_visibility);

void SerializeConfig(const ConfigDescription& config,
                     pb::ConfigDescription* out_pb_config);

bool DeserializeConfigDescriptionFromPb(const pb::ConfigDescription& pb_config,
                                        ConfigDescription* out_config);

pb::Reference_Type SerializeReferenceTypeToPb(Reference::Type type);

Reference::Type DeserializeReferenceTypeFromPb(pb::Reference_Type pb_type);

pb::Plural_Arity SerializePluralEnumToPb(size_t plural_idx);

size_t DeserializePluralEnumFromPb(pb::Plural_Arity arity);

}  // namespace aapt

#endif /* AAPT_PROTO_PROTOHELPERS_H */
