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

#include "proto/ProtoSerialize.h"

#include "android-base/logging.h"
#include "androidfw/ResourceTypes.h"

#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ValueVisitor.h"
#include "proto/ProtoHelpers.h"

namespace aapt {

namespace {

class ReferenceIdToNameVisitor : public ValueVisitor {
 public:
  using ValueVisitor::Visit;

  explicit ReferenceIdToNameVisitor(const std::map<ResourceId, ResourceNameRef>* mapping)
      : mapping_(mapping) {
    CHECK(mapping_ != nullptr);
  }

  void Visit(Reference* reference) override {
    if (!reference->id || !reference->id.value().is_valid()) {
      return;
    }

    ResourceId id = reference->id.value();
    auto cache_iter = mapping_->find(id);
    if (cache_iter != mapping_->end()) {
      reference->name = cache_iter->second.ToResourceName();
    }
  }

 private:
  const std::map<ResourceId, ResourceNameRef>* mapping_;
};

class PackagePbDeserializer {
 public:
  PackagePbDeserializer(const android::ResStringPool* valuePool,
                        const android::ResStringPool* sourcePool,
                        const android::ResStringPool* symbolPool,
                        const Source& source, IDiagnostics* diag)
      : value_pool_(valuePool),
        source_pool_(sourcePool),
        symbol_pool_(symbolPool),
        source_(source),
        diag_(diag) {}

 public:
  bool DeserializeFromPb(const pb::Package& pbPackage, ResourceTable* table) {
    Maybe<uint8_t> id;
    if (pbPackage.has_package_id()) {
      id = static_cast<uint8_t>(pbPackage.package_id());
    }

    std::map<ResourceId, ResourceNameRef> idIndex;

    ResourceTablePackage* pkg = table->CreatePackage(pbPackage.package_name(), id);
    for (const pb::Type& pbType : pbPackage.types()) {
      const ResourceType* resType = ParseResourceType(pbType.name());
      if (!resType) {
        diag_->Error(DiagMessage(source_) << "unknown type '" << pbType.name() << "'");
        return {};
      }

      ResourceTableType* type = pkg->FindOrCreateType(*resType);

      for (const pb::Entry& pbEntry : pbType.entries()) {
        ResourceEntry* entry = type->FindOrCreateEntry(pbEntry.name());

        // Deserialize the symbol status (public/private with source and
        // comments).
        if (pbEntry.has_symbol_status()) {
          const pb::SymbolStatus& pbStatus = pbEntry.symbol_status();
          if (pbStatus.has_source()) {
            DeserializeSourceFromPb(pbStatus.source(), *source_pool_, &entry->symbol_status.source);
          }

          if (pbStatus.has_comment()) {
            entry->symbol_status.comment = pbStatus.comment();
          }

          entry->symbol_status.allow_new = pbStatus.allow_new();

          SymbolState visibility = DeserializeVisibilityFromPb(pbStatus.visibility());
          entry->symbol_status.state = visibility;

          if (visibility == SymbolState::kPublic) {
            // This is a public symbol, we must encode the ID now if there is one.
            if (pbEntry.has_id()) {
              entry->id = static_cast<uint16_t>(pbEntry.id());
            }

            if (type->symbol_status.state != SymbolState::kPublic) {
              // If the type has not been made public, do so now.
              type->symbol_status.state = SymbolState::kPublic;
              if (pbType.has_id()) {
                type->id = static_cast<uint8_t>(pbType.id());
              }
            }
          } else if (visibility == SymbolState::kPrivate) {
            if (type->symbol_status.state == SymbolState::kUndefined) {
              type->symbol_status.state = SymbolState::kPrivate;
            }
          }
        }

        ResourceId resId(pbPackage.package_id(), pbType.id(), pbEntry.id());
        if (resId.is_valid()) {
          idIndex[resId] = ResourceNameRef(pkg->name, type->type, entry->name);
        }

        for (const pb::ConfigValue& pbConfigValue : pbEntry.config_values()) {
          const pb::ConfigDescription& pbConfig = pbConfigValue.config();

          ConfigDescription config;
          if (!DeserializeConfigDescriptionFromPb(pbConfig, &config)) {
            diag_->Error(DiagMessage(source_) << "invalid configuration");
            return {};
          }

          ResourceConfigValue* configValue = entry->FindOrCreateValue(config, pbConfig.product());
          if (configValue->value) {
            // Duplicate config.
            diag_->Error(DiagMessage(source_) << "duplicate configuration");
            return {};
          }

          configValue->value =
              DeserializeValueFromPb(pbConfigValue.value(), config, &table->string_pool);
          if (!configValue->value) {
            return {};
          }
        }
      }
    }

    ReferenceIdToNameVisitor visitor(&idIndex);
    VisitAllValuesInPackage(pkg, &visitor);
    return true;
  }

 private:
  std::unique_ptr<Item> DeserializeItemFromPb(const pb::Item& pb_item,
                                              const ConfigDescription& config,
                                              StringPool* pool) {
    if (pb_item.has_ref()) {
      const pb::Reference& pb_ref = pb_item.ref();
      std::unique_ptr<Reference> ref = util::make_unique<Reference>();
      if (!DeserializeReferenceFromPb(pb_ref, ref.get())) {
        return {};
      }
      return std::move(ref);

    } else if (pb_item.has_prim()) {
      const pb::Primitive& pb_prim = pb_item.prim();
      android::Res_value prim = {};
      prim.dataType = static_cast<uint8_t>(pb_prim.type());
      prim.data = pb_prim.data();
      return util::make_unique<BinaryPrimitive>(prim);

    } else if (pb_item.has_id()) {
      return util::make_unique<Id>();

    } else if (pb_item.has_str()) {
      const uint32_t idx = pb_item.str().idx();
      const std::string str = util::GetString(*value_pool_, idx);

      const android::ResStringPool_span* spans = value_pool_->styleAt(idx);
      if (spans && spans->name.index != android::ResStringPool_span::END) {
        StyleString style_str = {str};
        while (spans->name.index != android::ResStringPool_span::END) {
          style_str.spans.push_back(
              Span{util::GetString(*value_pool_, spans->name.index),
                   spans->firstChar, spans->lastChar});
          spans++;
        }
        return util::make_unique<StyledString>(pool->MakeRef(
            style_str,
            StringPool::Context(StringPool::Context::kStylePriority, config)));
      }
      return util::make_unique<String>(
          pool->MakeRef(str, StringPool::Context(config)));

    } else if (pb_item.has_raw_str()) {
      const uint32_t idx = pb_item.raw_str().idx();
      const std::string str = util::GetString(*value_pool_, idx);
      return util::make_unique<RawString>(
          pool->MakeRef(str, StringPool::Context(config)));

    } else if (pb_item.has_file()) {
      const uint32_t idx = pb_item.file().path_idx();
      const std::string str = util::GetString(*value_pool_, idx);
      return util::make_unique<FileReference>(pool->MakeRef(
          str,
          StringPool::Context(StringPool::Context::kHighPriority, config)));

    } else {
      diag_->Error(DiagMessage(source_) << "unknown item");
    }
    return {};
  }

  std::unique_ptr<Value> DeserializeValueFromPb(const pb::Value& pb_value,
                                                const ConfigDescription& config,
                                                StringPool* pool) {
    const bool is_weak = pb_value.has_weak() ? pb_value.weak() : false;

    std::unique_ptr<Value> value;
    if (pb_value.has_item()) {
      value = DeserializeItemFromPb(pb_value.item(), config, pool);
      if (!value) {
        return {};
      }

    } else if (pb_value.has_compound_value()) {
      const pb::CompoundValue& pb_compound_value = pb_value.compound_value();
      if (pb_compound_value.has_attr()) {
        const pb::Attribute& pb_attr = pb_compound_value.attr();
        std::unique_ptr<Attribute> attr = util::make_unique<Attribute>(is_weak);
        attr->type_mask = pb_attr.format_flags();
        attr->min_int = pb_attr.min_int();
        attr->max_int = pb_attr.max_int();
        for (const pb::Attribute_Symbol& pb_symbol : pb_attr.symbols()) {
          Attribute::Symbol symbol;
          DeserializeItemCommon(pb_symbol, &symbol.symbol);
          if (!DeserializeReferenceFromPb(pb_symbol.name(), &symbol.symbol)) {
            return {};
          }
          symbol.value = pb_symbol.value();
          attr->symbols.push_back(std::move(symbol));
        }
        value = std::move(attr);

      } else if (pb_compound_value.has_style()) {
        const pb::Style& pb_style = pb_compound_value.style();
        std::unique_ptr<Style> style = util::make_unique<Style>();
        if (pb_style.has_parent()) {
          style->parent = Reference();
          if (!DeserializeReferenceFromPb(pb_style.parent(),
                                          &style->parent.value())) {
            return {};
          }

          if (pb_style.has_parent_source()) {
            Source parent_source;
            DeserializeSourceFromPb(pb_style.parent_source(), *source_pool_,
                                    &parent_source);
            style->parent.value().SetSource(std::move(parent_source));
          }
        }

        for (const pb::Style_Entry& pb_entry : pb_style.entries()) {
          Style::Entry entry;
          DeserializeItemCommon(pb_entry, &entry.key);
          if (!DeserializeReferenceFromPb(pb_entry.key(), &entry.key)) {
            return {};
          }

          entry.value = DeserializeItemFromPb(pb_entry.item(), config, pool);
          if (!entry.value) {
            return {};
          }

          DeserializeItemCommon(pb_entry, entry.value.get());
          style->entries.push_back(std::move(entry));
        }
        value = std::move(style);

      } else if (pb_compound_value.has_styleable()) {
        const pb::Styleable& pb_styleable = pb_compound_value.styleable();
        std::unique_ptr<Styleable> styleable = util::make_unique<Styleable>();
        for (const pb::Styleable_Entry& pb_entry : pb_styleable.entries()) {
          Reference attr_ref;
          DeserializeItemCommon(pb_entry, &attr_ref);
          DeserializeReferenceFromPb(pb_entry.attr(), &attr_ref);
          styleable->entries.push_back(std::move(attr_ref));
        }
        value = std::move(styleable);

      } else if (pb_compound_value.has_array()) {
        const pb::Array& pb_array = pb_compound_value.array();
        std::unique_ptr<Array> array = util::make_unique<Array>();
        for (const pb::Array_Entry& pb_entry : pb_array.entries()) {
          std::unique_ptr<Item> item =
              DeserializeItemFromPb(pb_entry.item(), config, pool);
          if (!item) {
            return {};
          }

          DeserializeItemCommon(pb_entry, item.get());
          array->items.push_back(std::move(item));
        }
        value = std::move(array);

      } else if (pb_compound_value.has_plural()) {
        const pb::Plural& pb_plural = pb_compound_value.plural();
        std::unique_ptr<Plural> plural = util::make_unique<Plural>();
        for (const pb::Plural_Entry& pb_entry : pb_plural.entries()) {
          size_t pluralIdx = DeserializePluralEnumFromPb(pb_entry.arity());
          plural->values[pluralIdx] =
              DeserializeItemFromPb(pb_entry.item(), config, pool);
          if (!plural->values[pluralIdx]) {
            return {};
          }

          DeserializeItemCommon(pb_entry, plural->values[pluralIdx].get());
        }
        value = std::move(plural);

      } else {
        diag_->Error(DiagMessage(source_) << "unknown compound value");
        return {};
      }
    } else {
      diag_->Error(DiagMessage(source_) << "unknown value");
      return {};
    }

    CHECK(value) << "forgot to set value";

    value->SetWeak(is_weak);
    DeserializeItemCommon(pb_value, value.get());
    return value;
  }

  bool DeserializeReferenceFromPb(const pb::Reference& pb_ref, Reference* out_ref) {
    out_ref->reference_type = DeserializeReferenceTypeFromPb(pb_ref.type());
    out_ref->private_reference = pb_ref.private_();

    if (pb_ref.has_id()) {
      out_ref->id = ResourceId(pb_ref.id());
    }

    if (pb_ref.has_symbol_idx()) {
      const std::string str_symbol = util::GetString(*symbol_pool_, pb_ref.symbol_idx());
      ResourceNameRef name_ref;
      if (!ResourceUtils::ParseResourceName(str_symbol, &name_ref, nullptr)) {
        diag_->Error(DiagMessage(source_) << "invalid reference name '" << str_symbol << "'");
        return false;
      }

      out_ref->name = name_ref.ToResourceName();
    }
    return true;
  }

  template <typename T>
  void DeserializeItemCommon(const T& pb_item, Value* out_value) {
    if (pb_item.has_source()) {
      Source source;
      DeserializeSourceFromPb(pb_item.source(), *source_pool_, &source);
      out_value->SetSource(std::move(source));
    }

    if (pb_item.has_comment()) {
      out_value->SetComment(pb_item.comment());
    }
  }

 private:
  const android::ResStringPool* value_pool_;
  const android::ResStringPool* source_pool_;
  const android::ResStringPool* symbol_pool_;
  const Source source_;
  IDiagnostics* diag_;
};

}  // namespace

std::unique_ptr<ResourceTable> DeserializeTableFromPb(
    const pb::ResourceTable& pb_table, const Source& source,
    IDiagnostics* diag) {
  // We import the android namespace because on Windows NO_ERROR is a macro, not
  // an enum, which
  // causes errors when qualifying it with android::
  using namespace android;

  std::unique_ptr<ResourceTable> table = util::make_unique<ResourceTable>();

  if (!pb_table.has_string_pool()) {
    diag->Error(DiagMessage(source) << "no string pool found");
    return {};
  }

  ResStringPool value_pool;
  status_t result = value_pool.setTo(pb_table.string_pool().data().data(),
                                     pb_table.string_pool().data().size());
  if (result != NO_ERROR) {
    diag->Error(DiagMessage(source) << "invalid string pool");
    return {};
  }

  ResStringPool source_pool;
  if (pb_table.has_source_pool()) {
    result = source_pool.setTo(pb_table.source_pool().data().data(),
                               pb_table.source_pool().data().size());
    if (result != NO_ERROR) {
      diag->Error(DiagMessage(source) << "invalid source pool");
      return {};
    }
  }

  ResStringPool symbol_pool;
  if (pb_table.has_symbol_pool()) {
    result = symbol_pool.setTo(pb_table.symbol_pool().data().data(),
                               pb_table.symbol_pool().data().size());
    if (result != NO_ERROR) {
      diag->Error(DiagMessage(source) << "invalid symbol pool");
      return {};
    }
  }

  PackagePbDeserializer package_pb_deserializer(&value_pool, &source_pool,
                                                &symbol_pool, source, diag);
  for (const pb::Package& pb_package : pb_table.packages()) {
    if (!package_pb_deserializer.DeserializeFromPb(pb_package, table.get())) {
      return {};
    }
  }
  return table;
}

std::unique_ptr<ResourceFile> DeserializeCompiledFileFromPb(
    const pb::CompiledFile& pb_file, const Source& source, IDiagnostics* diag) {
  std::unique_ptr<ResourceFile> file = util::make_unique<ResourceFile>();

  ResourceNameRef name_ref;

  // Need to create an lvalue here so that nameRef can point to something real.
  if (!ResourceUtils::ParseResourceName(pb_file.resource_name(), &name_ref)) {
    diag->Error(DiagMessage(source)
                << "invalid resource name in compiled file header: "
                << pb_file.resource_name());
    return {};
  }
  file->name = name_ref.ToResourceName();
  file->source.path = pb_file.source_path();
  DeserializeConfigDescriptionFromPb(pb_file.config(), &file->config);

  for (const pb::CompiledFile_Symbol& pb_symbol : pb_file.exported_symbols()) {
    // Need to create an lvalue here so that nameRef can point to something
    // real.
    if (!ResourceUtils::ParseResourceName(pb_symbol.resource_name(),
                                          &name_ref)) {
      diag->Error(DiagMessage(source)
                  << "invalid resource name for exported symbol in "
                     "compiled file header: "
                  << pb_file.resource_name());
      return {};
    }
    file->exported_symbols.push_back(
        SourcedResourceName{name_ref.ToResourceName(), pb_symbol.line_no()});
  }
  return file;
}

}  // namespace aapt
