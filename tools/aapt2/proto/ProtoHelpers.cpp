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

void serializeStringPoolToPb(const StringPool& pool, pb::StringPool* outPbPool) {
    BigBuffer buffer(1024);
    StringPool::flattenUtf8(&buffer, pool);

    std::string* data = outPbPool->mutable_data();
    data->reserve(buffer.size());

    size_t offset = 0;
    for (const BigBuffer::Block& block : buffer) {
        data->insert(data->begin() + offset, block.buffer.get(), block.buffer.get() + block.size);
        offset += block.size;
    }
}

void serializeSourceToPb(const Source& source, StringPool* srcPool, pb::Source* outPbSource) {
    StringPool::Ref ref = srcPool->makeRef(util::utf8ToUtf16(source.path));
    outPbSource->set_path_idx(static_cast<uint32_t>(ref.getIndex()));
    if (source.line) {
        outPbSource->set_line_no(static_cast<uint32_t>(source.line.value()));
    }
}

void deserializeSourceFromPb(const pb::Source& pbSource, const android::ResStringPool& srcPool,
                             Source* outSource) {
    if (pbSource.has_path_idx()) {
        outSource->path = util::getString8(srcPool, pbSource.path_idx()).toString();
    }

    if (pbSource.has_line_no()) {
        outSource->line = static_cast<size_t>(pbSource.line_no());
    }
}

pb::SymbolStatus_Visibility serializeVisibilityToPb(SymbolState state) {
    switch (state) {
    case SymbolState::kPrivate: return pb::SymbolStatus_Visibility_Private;
    case SymbolState::kPublic: return pb::SymbolStatus_Visibility_Public;
    default: break;
    }
    return pb::SymbolStatus_Visibility_Unknown;
}

SymbolState deserializeVisibilityFromPb(pb::SymbolStatus_Visibility pbVisibility) {
    switch (pbVisibility) {
    case pb::SymbolStatus_Visibility_Private: return SymbolState::kPrivate;
    case pb::SymbolStatus_Visibility_Public: return SymbolState::kPublic;
    default: break;
    }
    return SymbolState::kUndefined;
}

void serializeConfig(const ConfigDescription& config, pb::ConfigDescription* outPbConfig) {
    android::ResTable_config flatConfig = config;
    flatConfig.size = sizeof(flatConfig);
    flatConfig.swapHtoD();
    outPbConfig->set_data(&flatConfig, sizeof(flatConfig));
}

bool deserializeConfigDescriptionFromPb(const pb::ConfigDescription& pbConfig,
                                        ConfigDescription* outConfig) {
    if (!pbConfig.has_data()) {
        return false;
    }

    const android::ResTable_config* config;
    if (pbConfig.data().size() > sizeof(*config)) {
        return false;
    }

    config = reinterpret_cast<const android::ResTable_config*>(pbConfig.data().data());
    outConfig->copyFromDtoH(*config);
    return true;
}

pb::Reference_Type serializeReferenceTypeToPb(Reference::Type type) {
    switch (type) {
    case Reference::Type::kResource:  return pb::Reference_Type_Ref;
    case Reference::Type::kAttribute: return pb::Reference_Type_Attr;
    default: break;
    }
    return pb::Reference_Type_Ref;
}

Reference::Type deserializeReferenceTypeFromPb(pb::Reference_Type pbType) {
    switch (pbType) {
    case pb::Reference_Type_Ref:  return Reference::Type::kResource;
    case pb::Reference_Type_Attr: return Reference::Type::kAttribute;
    default: break;
    }
    return Reference::Type::kResource;
}

pb::Plural_Arity serializePluralEnumToPb(size_t pluralIdx) {
    switch (pluralIdx) {
    case Plural::Zero:  return pb::Plural_Arity_Zero;
    case Plural::One:   return pb::Plural_Arity_One;
    case Plural::Two:   return pb::Plural_Arity_Two;
    case Plural::Few:   return pb::Plural_Arity_Few;
    case Plural::Many:  return pb::Plural_Arity_Many;
    default: break;
    }
    return pb::Plural_Arity_Other;
}

size_t deserializePluralEnumFromPb(pb::Plural_Arity arity) {
    switch (arity) {
    case pb::Plural_Arity_Zero: return Plural::Zero;
    case pb::Plural_Arity_One:  return Plural::One;
    case pb::Plural_Arity_Two:  return Plural::Two;
    case pb::Plural_Arity_Few:  return Plural::Few;
    case pb::Plural_Arity_Many: return Plural::Many;
    default: break;
    }
    return Plural::Other;
}

} // namespace aapt
