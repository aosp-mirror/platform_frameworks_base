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

#include "ConfigDescription.h"
#include "ResourceTable.h"
#include "Source.h"
#include "StringPool.h"

#include "proto/frameworks/base/tools/aapt2/Format.pb.h"

#include <androidfw/ResourceTypes.h>

namespace aapt {

void serializeStringPoolToPb(const StringPool& pool, pb::StringPool* outPbPool);

void serializeSourceToPb(const Source& source, StringPool* srcPool, pb::Source* outPbSource);
void deserializeSourceFromPb(const pb::Source& pbSource, const android::ResStringPool& srcPool,
                             Source* outSource);

pb::SymbolStatus_Visibility serializeVisibilityToPb(SymbolState state);
SymbolState deserializeVisibilityFromPb(pb::SymbolStatus_Visibility pbVisibility);

void serializeConfig(const ConfigDescription& config, pb::ConfigDescription* outPbConfig);
bool deserializeConfigDescriptionFromPb(const pb::ConfigDescription& pbConfig,
                                        ConfigDescription* outConfig);

pb::Reference_Type serializeReferenceTypeToPb(Reference::Type type);
Reference::Type deserializeReferenceTypeFromPb(pb::Reference_Type pbType);

pb::Plural_Arity serializePluralEnumToPb(size_t pluralIdx);
size_t deserializePluralEnumFromPb(pb::Plural_Arity arity);

} // namespace aapt

#endif /* AAPT_PROTO_PROTOHELPERS_H */
