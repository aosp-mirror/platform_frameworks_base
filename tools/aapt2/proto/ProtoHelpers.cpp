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

#include "proto/ProtoHelpers.h"

namespace aapt {

void SerializeStringPoolToPb(const StringPool& pool, pb::StringPool* out_pb_pool) {
  BigBuffer buffer(1024);
  StringPool::FlattenUtf8(&buffer, pool);

  std::string* data = out_pb_pool->mutable_data();
  data->reserve(buffer.size());

  size_t offset = 0;
  for (const BigBuffer::Block& block : buffer) {
    data->insert(data->begin() + offset, block.buffer.get(), block.buffer.get() + block.size);
    offset += block.size;
  }
}

void SerializeSourceToPb(const Source& source, StringPool* src_pool, pb::Source* out_pb_source) {
  StringPool::Ref ref = src_pool->MakeRef(source.path);
  out_pb_source->set_path_idx(static_cast<uint32_t>(ref.index()));
  if (source.line) {
    out_pb_source->mutable_position()->set_line_number(static_cast<uint32_t>(source.line.value()));
  }
}

void DeserializeSourceFromPb(const pb::Source& pb_source, const android::ResStringPool& src_pool,
                             Source* out_source) {
  out_source->path = util::GetString(src_pool, pb_source.path_idx());
  out_source->line = static_cast<size_t>(pb_source.position().line_number());
}

pb::SymbolStatus_Visibility SerializeVisibilityToPb(SymbolState state) {
  switch (state) {
    case SymbolState::kPrivate:
      return pb::SymbolStatus_Visibility_PRIVATE;
    case SymbolState::kPublic:
      return pb::SymbolStatus_Visibility_PUBLIC;
    default:
      break;
  }
  return pb::SymbolStatus_Visibility_UNKNOWN;
}

SymbolState DeserializeVisibilityFromPb(pb::SymbolStatus_Visibility pb_visibility) {
  switch (pb_visibility) {
    case pb::SymbolStatus_Visibility_PRIVATE:
      return SymbolState::kPrivate;
    case pb::SymbolStatus_Visibility_PUBLIC:
      return SymbolState::kPublic;
    default:
      break;
  }
  return SymbolState::kUndefined;
}

void SerializeConfig(const ConfigDescription& config, pb::ConfigDescription* out_pb_config) {
  android::ResTable_config flat_config = config;
  flat_config.size = sizeof(flat_config);
  flat_config.swapHtoD();
  out_pb_config->set_data(&flat_config, sizeof(flat_config));
}

bool DeserializeConfigDescriptionFromPb(const pb::ConfigDescription& pb_config,
                                        ConfigDescription* out_config) {
  // a ConfigDescription must be at least 4 bytes to store the size.
  if (pb_config.data().size() < 4) {
    return false;
  }

  const android::ResTable_config* config;
  if (pb_config.data().size() > sizeof(*config)) {
    return false;
  }

  config = reinterpret_cast<const android::ResTable_config*>(pb_config.data().data());
  out_config->copyFromDtoH(*config);
  return true;
}

pb::Reference_Type SerializeReferenceTypeToPb(Reference::Type type) {
  switch (type) {
    case Reference::Type::kResource:
      return pb::Reference_Type_REFERENCE;
    case Reference::Type::kAttribute:
      return pb::Reference_Type_ATTRIBUTE;
    default:
      break;
  }
  return pb::Reference_Type_REFERENCE;
}

Reference::Type DeserializeReferenceTypeFromPb(pb::Reference_Type pb_type) {
  switch (pb_type) {
    case pb::Reference_Type_REFERENCE:
      return Reference::Type::kResource;
    case pb::Reference_Type_ATTRIBUTE:
      return Reference::Type::kAttribute;
    default:
      break;
  }
  return Reference::Type::kResource;
}

pb::Plural_Arity SerializePluralEnumToPb(size_t plural_idx) {
  switch (plural_idx) {
    case Plural::Zero:
      return pb::Plural_Arity_ZERO;
    case Plural::One:
      return pb::Plural_Arity_ONE;
    case Plural::Two:
      return pb::Plural_Arity_TWO;
    case Plural::Few:
      return pb::Plural_Arity_FEW;
    case Plural::Many:
      return pb::Plural_Arity_MANY;
    default:
      break;
  }
  return pb::Plural_Arity_OTHER;
}

size_t DeserializePluralEnumFromPb(pb::Plural_Arity arity) {
  switch (arity) {
    case pb::Plural_Arity_ZERO:
      return Plural::Zero;
    case pb::Plural_Arity_ONE:
      return Plural::One;
    case pb::Plural_Arity_TWO:
      return Plural::Two;
    case pb::Plural_Arity_FEW:
      return Plural::Few;
    case pb::Plural_Arity_MANY:
      return Plural::Many;
    default:
      break;
  }
  return Plural::Other;
}

}  // namespace aapt
