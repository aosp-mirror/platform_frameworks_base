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
  PackagePbDeserializer(const android::ResStringPool* sourcePool, const Source& source,
                        IDiagnostics* diag)
      : source_pool_(sourcePool), source_(source), diag_(diag) {
  }

 public:
  bool DeserializeFromPb(const pb::Package& pb_package, ResourceTable* table) {
    Maybe<uint8_t> id;
    if (pb_package.has_package_id()) {
      id = static_cast<uint8_t>(pb_package.package_id());
    }

    std::map<ResourceId, ResourceNameRef> id_index;

    ResourceTablePackage* pkg = table->CreatePackage(pb_package.package_name(), id);
    for (const pb::Type& pb_type : pb_package.type()) {
      const ResourceType* res_type = ParseResourceType(pb_type.name());
      if (res_type == nullptr) {
        diag_->Error(DiagMessage(source_) << "unknown type '" << pb_type.name() << "'");
        return {};
      }

      ResourceTableType* type = pkg->FindOrCreateType(*res_type);

      for (const pb::Entry& pb_entry : pb_type.entry()) {
        ResourceEntry* entry = type->FindOrCreateEntry(pb_entry.name());

        // Deserialize the symbol status (public/private with source and comments).
        if (pb_entry.has_symbol_status()) {
          const pb::SymbolStatus& pb_status = pb_entry.symbol_status();
          if (pb_status.has_source()) {
            DeserializeSourceFromPb(pb_status.source(), *source_pool_,
                                    &entry->symbol_status.source);
          }

          if (pb_status.has_comment()) {
            entry->symbol_status.comment = pb_status.comment();
          }

          entry->symbol_status.allow_new = pb_status.allow_new();

          SymbolState visibility = DeserializeVisibilityFromPb(pb_status.visibility());
          entry->symbol_status.state = visibility;

          if (visibility == SymbolState::kPublic) {
            // This is a public symbol, we must encode the ID now if there is one.
            if (pb_entry.has_id()) {
              entry->id = static_cast<uint16_t>(pb_entry.id());
            }

            if (type->symbol_status.state != SymbolState::kPublic) {
              // If the type has not been made public, do so now.
              type->symbol_status.state = SymbolState::kPublic;
              if (pb_type.has_id()) {
                type->id = static_cast<uint8_t>(pb_type.id());
              }
            }
          } else if (visibility == SymbolState::kPrivate) {
            if (type->symbol_status.state == SymbolState::kUndefined) {
              type->symbol_status.state = SymbolState::kPrivate;
            }
          }
        }

        ResourceId resid(pb_package.package_id(), pb_type.id(), pb_entry.id());
        if (resid.is_valid()) {
          id_index[resid] = ResourceNameRef(pkg->name, type->type, entry->name);
        }

        for (const pb::ConfigValue& pb_config_value : pb_entry.config_value()) {
          const pb::ConfigDescription& pb_config = pb_config_value.config();

          ConfigDescription config;
          if (!DeserializeConfigDescriptionFromPb(pb_config, &config)) {
            diag_->Error(DiagMessage(source_) << "invalid configuration");
            return {};
          }

          ResourceConfigValue* config_value = entry->FindOrCreateValue(config, pb_config.product());
          if (config_value->value) {
            // Duplicate config.
            diag_->Error(DiagMessage(source_) << "duplicate configuration");
            return {};
          }

          config_value->value =
              DeserializeValueFromPb(pb_config_value.value(), config, &table->string_pool);
          if (!config_value->value) {
            return {};
          }
        }
      }
    }

    ReferenceIdToNameVisitor visitor(&id_index);
    VisitAllValuesInPackage(pkg, &visitor);
    return true;
  }

 private:
  std::unique_ptr<Item> DeserializeItemFromPb(const pb::Item& pb_item,
                                              const ConfigDescription& config, StringPool* pool) {
    if (pb_item.has_ref()) {
      const pb::Reference& pb_ref = pb_item.ref();
      std::unique_ptr<Reference> ref = util::make_unique<Reference>();
      if (!DeserializeReferenceFromPb(pb_ref, ref.get())) {
        return {};
      }
      return std::move(ref);

    } else if (pb_item.has_prim()) {
      const pb::Primitive& pb_prim = pb_item.prim();
      return util::make_unique<BinaryPrimitive>(static_cast<uint8_t>(pb_prim.type()),
                                                pb_prim.data());

    } else if (pb_item.has_id()) {
      return util::make_unique<Id>();

    } else if (pb_item.has_str()) {
      return util::make_unique<String>(
          pool->MakeRef(pb_item.str().value(), StringPool::Context(config)));

    } else if (pb_item.has_raw_str()) {
      return util::make_unique<RawString>(
          pool->MakeRef(pb_item.raw_str().value(), StringPool::Context(config)));

    } else if (pb_item.has_styled_str()) {
      const pb::StyledString& pb_str = pb_item.styled_str();
      StyleString style_str{pb_str.value()};
      for (const pb::StyledString::Span& pb_span : pb_str.span()) {
        style_str.spans.push_back(Span{pb_span.tag(), pb_span.first_char(), pb_span.last_char()});
      }
      return util::make_unique<StyledString>(pool->MakeRef(
          style_str, StringPool::Context(StringPool::Context::kNormalPriority, config)));

    } else if (pb_item.has_file()) {
      return util::make_unique<FileReference>(pool->MakeRef(
          pb_item.file().path(), StringPool::Context(StringPool::Context::kHighPriority, config)));

    } else {
      diag_->Error(DiagMessage(source_) << "unknown item");
    }
    return {};
  }

  std::unique_ptr<Value> DeserializeValueFromPb(const pb::Value& pb_value,
                                                const ConfigDescription& config,
                                                StringPool* pool) {
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
        std::unique_ptr<Attribute> attr = util::make_unique<Attribute>();
        attr->type_mask = pb_attr.format_flags();
        attr->min_int = pb_attr.min_int();
        attr->max_int = pb_attr.max_int();
        for (const pb::Attribute_Symbol& pb_symbol : pb_attr.symbol()) {
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
          if (!DeserializeReferenceFromPb(pb_style.parent(), &style->parent.value())) {
            return {};
          }

          if (pb_style.has_parent_source()) {
            Source parent_source;
            DeserializeSourceFromPb(pb_style.parent_source(), *source_pool_, &parent_source);
            style->parent.value().SetSource(std::move(parent_source));
          }
        }

        for (const pb::Style_Entry& pb_entry : pb_style.entry()) {
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
        for (const pb::Styleable_Entry& pb_entry : pb_styleable.entry()) {
          Reference attr_ref;
          DeserializeItemCommon(pb_entry, &attr_ref);
          DeserializeReferenceFromPb(pb_entry.attr(), &attr_ref);
          styleable->entries.push_back(std::move(attr_ref));
        }
        value = std::move(styleable);

      } else if (pb_compound_value.has_array()) {
        const pb::Array& pb_array = pb_compound_value.array();
        std::unique_ptr<Array> array = util::make_unique<Array>();
        for (const pb::Array_Element& pb_entry : pb_array.element()) {
          std::unique_ptr<Item> item = DeserializeItemFromPb(pb_entry.item(), config, pool);
          if (!item) {
            return {};
          }

          DeserializeItemCommon(pb_entry, item.get());
          array->elements.push_back(std::move(item));
        }
        value = std::move(array);

      } else if (pb_compound_value.has_plural()) {
        const pb::Plural& pb_plural = pb_compound_value.plural();
        std::unique_ptr<Plural> plural = util::make_unique<Plural>();
        for (const pb::Plural_Entry& pb_entry : pb_plural.entry()) {
          size_t pluralIdx = DeserializePluralEnumFromPb(pb_entry.arity());
          plural->values[pluralIdx] = DeserializeItemFromPb(pb_entry.item(), config, pool);
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

    value->SetWeak(pb_value.weak());
    DeserializeItemCommon(pb_value, value.get());
    return value;
  }

  bool DeserializeReferenceFromPb(const pb::Reference& pb_ref, Reference* out_ref) {
    out_ref->reference_type = DeserializeReferenceTypeFromPb(pb_ref.type());
    out_ref->private_reference = pb_ref.private_();

    if (pb_ref.has_id()) {
      out_ref->id = ResourceId(pb_ref.id());
    }

    if (pb_ref.has_name()) {
      ResourceNameRef name_ref;
      if (!ResourceUtils::ParseResourceName(pb_ref.name(), &name_ref, nullptr)) {
        diag_->Error(DiagMessage(source_) << "invalid reference name '" << pb_ref.name() << "'");
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
  const android::ResStringPool* source_pool_;
  const Source source_;
  IDiagnostics* diag_;
};

}  // namespace

std::unique_ptr<ResourceTable> DeserializeTableFromPb(const pb::ResourceTable& pb_table,
                                                      const Source& source, IDiagnostics* diag) {
  // We import the android namespace because on Windows NO_ERROR is a macro, not an enum, which
  // causes errors when qualifying it with android::
  using namespace android;

  std::unique_ptr<ResourceTable> table = util::make_unique<ResourceTable>();

  ResStringPool source_pool;
  if (pb_table.has_source_pool()) {
    status_t result = source_pool.setTo(pb_table.source_pool().data().data(),
                                        pb_table.source_pool().data().size());
    if (result != NO_ERROR) {
      diag->Error(DiagMessage(source) << "invalid source pool");
      return {};
    }
  }

  PackagePbDeserializer package_pb_deserializer(&source_pool, source, diag);
  for (const pb::Package& pb_package : pb_table.package()) {
    if (!package_pb_deserializer.DeserializeFromPb(pb_package, table.get())) {
      return {};
    }
  }
  return table;
}

std::unique_ptr<ResourceFile> DeserializeCompiledFileFromPb(
    const pb::internal::CompiledFile& pb_file, const Source& source, IDiagnostics* diag) {
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

  for (const pb::internal::CompiledFile_Symbol& pb_symbol : pb_file.exported_symbol()) {
    // Need to create an lvalue here so that nameRef can point to something real.
    if (!ResourceUtils::ParseResourceName(pb_symbol.resource_name(), &name_ref)) {
      diag->Error(DiagMessage(source)
                  << "invalid resource name for exported symbol in "
                     "compiled file header: "
                  << pb_file.resource_name());
      return {};
    }
    size_t line = 0u;
    if (pb_symbol.has_source()) {
      line = pb_symbol.source().line_number();
    }
    file->exported_symbols.push_back(SourcedResourceName{name_ref.ToResourceName(), line});
  }
  return file;
}

}  // namespace aapt
