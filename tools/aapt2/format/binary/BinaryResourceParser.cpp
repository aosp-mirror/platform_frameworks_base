/*
 * Copyright (C) 2015 The Android Open Source Project
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

#include "format/binary/BinaryResourceParser.h"

#include <algorithm>
#include <map>
#include <optional>
#include <string>

#include "ResourceTable.h"
#include "ResourceUtils.h"
#include "ResourceValues.h"
#include "ValueVisitor.h"
#include "android-base/logging.h"
#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/Source.h"
#include "androidfw/TypeWrappers.h"
#include "format/binary/ResChunkPullParser.h"
#include "util/Util.h"

using namespace android;

using ::android::base::StringPrintf;

namespace aapt {

namespace {

static std::u16string strcpy16_dtoh(const char16_t* src, size_t len) {
  size_t utf16_len = strnlen16(src, len);
  if (utf16_len == 0) {
    return {};
  }
  std::u16string dst;
  dst.resize(utf16_len);
  for (size_t i = 0; i < utf16_len; i++) {
    dst[i] = android::util::DeviceToHost16(src[i]);
  }
  return dst;
}

// Visitor that converts a reference's resource ID to a resource name, given a mapping from
// resource ID to resource name.
class ReferenceIdToNameVisitor : public DescendingValueVisitor {
 public:
  using DescendingValueVisitor::Visit;

  explicit ReferenceIdToNameVisitor(const std::map<ResourceId, ResourceName>* mapping)
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
      reference->name = cache_iter->second;
    }
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ReferenceIdToNameVisitor);

  const std::map<ResourceId, ResourceName>* mapping_;
};

}  // namespace

BinaryResourceParser::BinaryResourceParser(IDiagnostics* diag, ResourceTable* table,
                                           const android::Source& source, const void* data,
                                           size_t len, io::IFileCollection* files)
    : diag_(diag), table_(table), source_(source), data_(data), data_len_(len), files_(files) {
}

bool BinaryResourceParser::Parse() {
  ResChunkPullParser parser(data_, data_len_);

  if (!ResChunkPullParser::IsGoodEvent(parser.Next())) {
    diag_->Error(android::DiagMessage(source_) << "corrupt resources.arsc: " << parser.error());
    return false;
  }

  if (parser.chunk()->type != android::RES_TABLE_TYPE) {
    diag_->Error(android::DiagMessage(source_) << StringPrintf(
                     "unknown chunk of type 0x%02x", static_cast<int>(parser.chunk()->type)));
    return false;
  }

  if (!ParseTable(parser.chunk())) {
    return false;
  }

  if (parser.Next() != ResChunkPullParser::Event::kEndDocument) {
    if (parser.event() == ResChunkPullParser::Event::kBadDocument) {
      diag_->Warn(android::DiagMessage(source_)
                  << "invalid chunk trailing RES_TABLE_TYPE: " << parser.error());
    } else {
      diag_->Warn(android::DiagMessage(source_)
                  << StringPrintf("unexpected chunk of type 0x%02x trailing RES_TABLE_TYPE",
                                  static_cast<int>(parser.chunk()->type)));
    }
  }

  if (!staged_entries_to_remove_.empty()) {
    diag_->Error(android::DiagMessage(source_) << "didn't find " << staged_entries_to_remove_.size()
                                               << " original staged resources");
    return false;
  }

  return true;
}

// Parses the resource table, which contains all the packages, types, and entries.
bool BinaryResourceParser::ParseTable(const ResChunk_header* chunk) {
  const ResTable_header* table_header = ConvertTo<ResTable_header>(chunk);
  if (!table_header) {
    diag_->Error(android::DiagMessage(source_) << "corrupt ResTable_header chunk");
    return false;
  }

  ResChunkPullParser parser(GetChunkData(&table_header->header),
                            GetChunkDataLen(&table_header->header));
  while (ResChunkPullParser::IsGoodEvent(parser.Next())) {
    switch (android::util::DeviceToHost16(parser.chunk()->type)) {
      case android::RES_STRING_POOL_TYPE:
        if (value_pool_.getError() == NO_INIT) {
          status_t err = value_pool_.setTo(parser.chunk(),
                                           android::util::DeviceToHost32(parser.chunk()->size));
          if (err != NO_ERROR) {
            diag_->Error(android::DiagMessage(source_)
                         << "corrupt string pool in ResTable: " << value_pool_.getError());
            return false;
          }

          // Reserve some space for the strings we are going to add.
          table_->string_pool.HintWillAdd(value_pool_.size(), value_pool_.styleCount());
        } else {
          diag_->Warn(android::DiagMessage(source_) << "unexpected string pool in ResTable");
        }
        break;

      case android::RES_TABLE_PACKAGE_TYPE:
        if (!ParsePackage(parser.chunk())) {
          return false;
        }
        break;

      default:
        diag_->Warn(android::DiagMessage(source_)
                    << "unexpected chunk type "
                    << static_cast<int>(android::util::DeviceToHost16(parser.chunk()->type)));
        break;
    }
  }

  if (parser.event() == ResChunkPullParser::Event::kBadDocument) {
    diag_->Error(android::DiagMessage(source_) << "corrupt resource table: " << parser.error());
    return false;
  }
  return true;
}

bool BinaryResourceParser::ParsePackage(const ResChunk_header* chunk) {
  constexpr size_t kMinPackageSize =
      sizeof(ResTable_package) - sizeof(ResTable_package::typeIdOffset);
  const ResTable_package* package_header = ConvertTo<ResTable_package, kMinPackageSize>(chunk);
  if (!package_header) {
    diag_->Error(android::DiagMessage(source_) << "corrupt ResTable_package chunk");
    return false;
  }

  uint32_t package_id = android::util::DeviceToHost32(package_header->id);
  if (package_id > std::numeric_limits<uint8_t>::max()) {
    diag_->Error(android::DiagMessage(source_) << "package ID is too big (" << package_id << ")");
    return false;
  }

  // Extract the package name.
  std::u16string package_name = strcpy16_dtoh((const char16_t*)package_header->name,
                                              arraysize(package_header->name));

  ResourceTablePackage* package =
      table_->FindOrCreatePackage(android::util::Utf16ToUtf8(package_name));
  if (!package) {
    diag_->Error(android::DiagMessage(source_)
                 << "incompatible package '" << package_name << "' with ID " << package_id);
    return false;
  }

  // There can be multiple packages in a table, so
  // clear the type and key pool in case they were set from a previous package.
  type_pool_.uninit();
  key_pool_.uninit();

  ResChunkPullParser parser(GetChunkData(&package_header->header),
                            GetChunkDataLen(&package_header->header));
  while (ResChunkPullParser::IsGoodEvent(parser.Next())) {
    switch (android::util::DeviceToHost16(parser.chunk()->type)) {
      case android::RES_STRING_POOL_TYPE:
        if (type_pool_.getError() == NO_INIT) {
          status_t err =
              type_pool_.setTo(parser.chunk(), android::util::DeviceToHost32(parser.chunk()->size));
          if (err != NO_ERROR) {
            diag_->Error(android::DiagMessage(source_)
                         << "corrupt type string pool in "
                         << "ResTable_package: " << type_pool_.getError());
            return false;
          }
        } else if (key_pool_.getError() == NO_INIT) {
          status_t err =
              key_pool_.setTo(parser.chunk(), android::util::DeviceToHost32(parser.chunk()->size));
          if (err != NO_ERROR) {
            diag_->Error(android::DiagMessage(source_)
                         << "corrupt key string pool in "
                         << "ResTable_package: " << key_pool_.getError());
            return false;
          }
        } else {
          diag_->Warn(android::DiagMessage(source_) << "unexpected string pool");
        }
        break;

      case android::RES_TABLE_TYPE_SPEC_TYPE:
        if (!ParseTypeSpec(package, parser.chunk(), package_id)) {
          return false;
        }
        break;

      case android::RES_TABLE_TYPE_TYPE:
        if (!ParseType(package, parser.chunk(), package_id)) {
          return false;
        }
        break;

      case android::RES_TABLE_LIBRARY_TYPE:
        if (!ParseLibrary(parser.chunk())) {
          return false;
        }
        break;

      case android::RES_TABLE_OVERLAYABLE_TYPE:
        if (!ParseOverlayable(parser.chunk())) {
          return false;
        }
        break;

      case android::RES_TABLE_STAGED_ALIAS_TYPE:
        if (!ParseStagedAliases(parser.chunk())) {
          return false;
        }
        break;

      default:
        diag_->Warn(android::DiagMessage(source_)
                    << "unexpected chunk type "
                    << static_cast<int>(android::util::DeviceToHost16(parser.chunk()->type)));
        break;
    }
  }

  if (parser.event() == ResChunkPullParser::Event::kBadDocument) {
    diag_->Error(android::DiagMessage(source_) << "corrupt ResTable_package: " << parser.error());
    return false;
  }

  // Now go through the table and change local resource ID references to
  // symbolic references.
  ReferenceIdToNameVisitor visitor(&id_index_);
  VisitAllValuesInTable(table_, &visitor);
  return true;
}

bool BinaryResourceParser::ParseTypeSpec(const ResourceTablePackage* package,
                                         const ResChunk_header* chunk, uint8_t package_id) {
  if (type_pool_.getError() != NO_ERROR) {
    diag_->Error(android::DiagMessage(source_) << "missing type string pool");
    return false;
  }

  const ResTable_typeSpec* type_spec = ConvertTo<ResTable_typeSpec>(chunk);
  if (!type_spec) {
    diag_->Error(android::DiagMessage(source_) << "corrupt ResTable_typeSpec chunk");
    return false;
  }

  if (type_spec->id == 0) {
    diag_->Error(android::DiagMessage(source_)
                 << "ResTable_typeSpec has invalid id: " << type_spec->id);
    return false;
  }

  // The data portion of this chunk contains entry_count 32bit entries,
  // each one representing a set of flags.
  const size_t entry_count = dtohl(type_spec->entryCount);

  // There can only be 2^16 entries in a type, because that is the ID
  // space for entries (EEEE) in the resource ID 0xPPTTEEEE.
  if (entry_count > std::numeric_limits<uint16_t>::max()) {
    diag_->Error(android::DiagMessage(source_)
                 << "ResTable_typeSpec has too many entries (" << entry_count << ")");
    return false;
  }

  const size_t data_size = android::util::DeviceToHost32(type_spec->header.size) -
                           android::util::DeviceToHost16(type_spec->header.headerSize);
  if (entry_count * sizeof(uint32_t) > data_size) {
    diag_->Error(android::DiagMessage(source_) << "ResTable_typeSpec too small to hold entries.");
    return false;
  }

  // Record the type_spec_flags for later. We don't know resource names yet, and we need those
  // to mark resources as overlayable.
  const uint32_t* type_spec_flags = reinterpret_cast<const uint32_t*>(
      reinterpret_cast<uintptr_t>(type_spec) +
      android::util::DeviceToHost16(type_spec->header.headerSize));
  for (size_t i = 0; i < entry_count; i++) {
    ResourceId id(package_id, type_spec->id, static_cast<size_t>(i));
    entry_type_spec_flags_[id] = android::util::DeviceToHost32(type_spec_flags[i]);
  }
  return true;
}

bool BinaryResourceParser::ParseType(const ResourceTablePackage* package,
                                     const ResChunk_header* chunk, uint8_t package_id) {
  if (type_pool_.getError() != NO_ERROR) {
    diag_->Error(android::DiagMessage(source_) << "missing type string pool");
    return false;
  }

  if (key_pool_.getError() != NO_ERROR) {
    diag_->Error(android::DiagMessage(source_) << "missing key string pool");
    return false;
  }

  // Specify a manual size, because ResTable_type contains ResTable_config, which changes
  // a lot and has its own code to handle variable size.
  const ResTable_type* type = ConvertTo<ResTable_type, kResTableTypeMinSize>(chunk);
  if (!type) {
    diag_->Error(android::DiagMessage(source_) << "corrupt ResTable_type chunk");
    return false;
  }

  if (type->id == 0) {
    diag_->Error(android::DiagMessage(source_)
                 << "ResTable_type has invalid id: " << (int)type->id);
    return false;
  }

  ConfigDescription config;
  config.copyFromDtoH(type->config);

  const std::string type_str = android::util::GetString(type_pool_, type->id - 1);
  std::optional<ResourceNamedTypeRef> parsed_type = ParseResourceNamedType(type_str);
  if (!parsed_type) {
    diag_->Warn(android::DiagMessage(source_)
                << "invalid type name '" << type_str << "' for type with ID " << int(type->id));
    return true;
  }

  TypeVariant tv(type);
  for (auto it = tv.beginEntries(); it != tv.endEntries(); ++it) {
    const ResTable_entry* entry = *it;
    if (!entry) {
      continue;
    }

    const ResourceName name(package->name, *parsed_type,
        android::util::GetString(key_pool_, entry->key()));
    const ResourceId res_id(package_id, type->id, static_cast<uint16_t>(it.index()));

    std::unique_ptr<Value> resource_value;
    if (auto mapEntry = entry->map_entry()) {
      // TODO(adamlesinski): Check that the entry count is valid.
      resource_value = ParseMapEntry(name, config, mapEntry);
    } else {
      resource_value = ParseValue(name, config, entry->value());
    }

    if (!resource_value) {
      diag_->Error(android::DiagMessage(source_)
                   << "failed to parse value for resource " << name << " (" << res_id
                   << ") with configuration '" << config << "'");
      return false;
    }

    if (const auto to_remove_it = staged_entries_to_remove_.find({name, res_id});
        to_remove_it != staged_entries_to_remove_.end()) {
      staged_entries_to_remove_.erase(to_remove_it);
      continue;
    }

    NewResourceBuilder res_builder(name);
    res_builder.SetValue(std::move(resource_value), config)
        .SetId(res_id, OnIdConflict::CREATE_ENTRY)
        .SetAllowMangled(true);

    if (entry->flags() & ResTable_entry::FLAG_PUBLIC) {
      Visibility visibility{Visibility::Level::kPublic};

      auto spec_flags = entry_type_spec_flags_.find(res_id);
      if (spec_flags != entry_type_spec_flags_.end() &&
          spec_flags->second & ResTable_typeSpec::SPEC_STAGED_API) {
        visibility.staged_api = true;
      }

      res_builder.SetVisibility(visibility);
      // Erase the ID from the map once processed, so that we don't mark the same symbol more than
      // once.
      entry_type_spec_flags_.erase(res_id);
    }

    // Add this resource name->id mapping to the index so
    // that we can resolve all ID references to name references.
    auto cache_iter = id_index_.find(res_id);
    if (cache_iter == id_index_.end()) {
      id_index_.insert({res_id, name});
    }

    if (!table_->AddResource(res_builder.Build(), diag_)) {
      return false;
    }
  }
  return true;
}

bool BinaryResourceParser::ParseLibrary(const ResChunk_header* chunk) {
  DynamicRefTable dynamic_ref_table;
  if (dynamic_ref_table.load(reinterpret_cast<const ResTable_lib_header*>(chunk)) != NO_ERROR) {
    return false;
  }

  const KeyedVector<String16, uint8_t>& entries = dynamic_ref_table.entries();
  const size_t count = entries.size();
  for (size_t i = 0; i < count; i++) {
    table_->included_packages_[entries.valueAt(i)] =
        android::util::Utf16ToUtf8(StringPiece16(entries.keyAt(i).c_str()));
  }
  return true;
}

bool BinaryResourceParser::ParseOverlayable(const ResChunk_header* chunk) {
  const ResTable_overlayable_header* header = ConvertTo<ResTable_overlayable_header>(chunk);
  if (!header) {
    diag_->Error(android::DiagMessage(source_) << "corrupt ResTable_category_header chunk");
    return false;
  }

  auto overlayable = std::make_shared<Overlayable>();
  overlayable->name = android::util::Utf16ToUtf8(
      strcpy16_dtoh((const char16_t*)header->name, arraysize(header->name)));
  overlayable->actor = android::util::Utf16ToUtf8(
      strcpy16_dtoh((const char16_t*)header->actor, arraysize(header->name)));

  ResChunkPullParser parser(GetChunkData(chunk),
                            GetChunkDataLen(chunk));
  while (ResChunkPullParser::IsGoodEvent(parser.Next())) {
    if (android::util::DeviceToHost16(parser.chunk()->type) ==
        android::RES_TABLE_OVERLAYABLE_POLICY_TYPE) {
      const ResTable_overlayable_policy_header* policy_header =
          ConvertTo<ResTable_overlayable_policy_header>(parser.chunk());

      const ResTable_ref* const ref_begin = reinterpret_cast<const ResTable_ref*>(
          ((uint8_t*)policy_header) +
          android::util::DeviceToHost32(policy_header->header.headerSize));
      const ResTable_ref* const ref_end =
          ref_begin + android::util::DeviceToHost32(policy_header->entry_count);
      for (auto ref_iter = ref_begin; ref_iter != ref_end; ++ref_iter) {
        ResourceId res_id(android::util::DeviceToHost32(ref_iter->ident));
        const auto iter = id_index_.find(res_id);

        // If the overlayable chunk comes before the type chunks, the resource ids and resource name
        // pairing will not exist at this point.
        if (iter == id_index_.cend()) {
          diag_->Error(android::DiagMessage(source_)
                       << "failed to find resource name for overlayable"
                       << " resource " << res_id);
          return false;
        }

        OverlayableItem overlayable_item(overlayable);
        overlayable_item.policies = policy_header->policy_flags;
        if (!table_->AddResource(NewResourceBuilder(iter->second)
                                     .SetId(res_id, OnIdConflict::CREATE_ENTRY)
                                     .SetOverlayable(std::move(overlayable_item))
                                     .SetAllowMangled(true)
                                     .Build(),
                                 diag_)) {
          return false;
        }
      }
    }
  }

  return true;
}

bool BinaryResourceParser::ParseStagedAliases(const ResChunk_header* chunk) {
  auto header = ConvertTo<ResTable_staged_alias_header>(chunk);
  if (!header) {
    diag_->Error(android::DiagMessage(source_) << "corrupt ResTable_staged_alias_header chunk");
    return false;
  }

  const auto ref_begin = reinterpret_cast<const ResTable_staged_alias_entry*>(
      ((uint8_t*)header) + android::util::DeviceToHost32(header->header.headerSize));
  const auto ref_end = ref_begin + android::util::DeviceToHost32(header->count);
  for (auto ref_iter = ref_begin; ref_iter != ref_end; ++ref_iter) {
    const auto staged_id = ResourceId(android::util::DeviceToHost32(ref_iter->stagedResId));
    const auto finalized_id = ResourceId(android::util::DeviceToHost32(ref_iter->finalizedResId));

    // If the staged alias chunk comes before the type chunks, the resource ids and resource name
    // pairing will not exist at this point.
    const auto iter = id_index_.find(finalized_id);
    if (iter == id_index_.cend()) {
      diag_->Error(android::DiagMessage(source_) << "failed to find resource name for finalized"
                                                 << " resource ID " << finalized_id);
      return false;
    }

    // Set the staged id of the finalized resource.
    const auto& resource_name = iter->second;
    const StagedId staged_id_def{.id = staged_id};
    if (!table_->AddResource(NewResourceBuilder(resource_name)
                                 .SetId(finalized_id, OnIdConflict::CREATE_ENTRY)
                                 .SetStagedId(staged_id_def)
                                 .SetAllowMangled(true)
                                 .Build(),
                             diag_)) {
      return false;
    }

    // Since a the finalized resource entry is cloned and added to the resource table under the
    // staged resource id, remove the cloned resource entry from the table.
    if (!table_->RemoveResource(resource_name, staged_id)) {
      // If we haven't seen this resource yet let's add a record to skip it when parsing.
      staged_entries_to_remove_.insert({resource_name, staged_id});
    }
  }
  return true;
}

std::unique_ptr<Item> BinaryResourceParser::ParseValue(const ResourceNameRef& name,
                                                       const ConfigDescription& config,
                                                       const android::Res_value& value) {
  std::unique_ptr<Item> item = ResourceUtils::ParseBinaryResValue(
      name.type.type, config, value_pool_, value, &table_->string_pool);
  if (files_ != nullptr) {
    FileReference* file_ref = ValueCast<FileReference>(item.get());
    if (file_ref != nullptr) {
      file_ref->file = files_->FindFile(*file_ref->path);
      if (file_ref->file == nullptr) {
        diag_->Warn(android::DiagMessage() << "resource " << name << " for config '" << config
                                           << "' is a file reference to '" << *file_ref->path
                                           << "' but no such path exists");
      }
    }
  }
  return item;
}

std::unique_ptr<Value> BinaryResourceParser::ParseMapEntry(const ResourceNameRef& name,
                                                           const ConfigDescription& config,
                                                           const ResTable_map_entry* map) {
  switch (name.type.type) {
    case ResourceType::kStyle:
      // fallthrough
    case ResourceType::kConfigVarying:  // legacy thing used in tests
      return ParseStyle(name, config, map);
    case ResourceType::kAttrPrivate:
      // fallthrough
    case ResourceType::kAttr:
      return ParseAttr(name, config, map);
    case ResourceType::kArray:
      return ParseArray(name, config, map);
    case ResourceType::kPlurals:
      return ParsePlural(name, config, map);
    case ResourceType::kId:
      // Special case: An ID is not a bag, but some apps have defined the auto-generated
      // IDs that come from declaring an enum value in an attribute as an empty map...
      // We can ignore the value here.
      return util::make_unique<Id>();
    default:
      diag_->Error(android::DiagMessage()
                   << "illegal map type '" << name.type << "' (" << (int)name.type.type << ")");
      break;
  }
  return {};
}

std::unique_ptr<Style> BinaryResourceParser::ParseStyle(const ResourceNameRef& name,
                                                        const ConfigDescription& config,
                                                        const ResTable_map_entry* map) {
  std::unique_ptr<Style> style = util::make_unique<Style>();
  if (android::util::DeviceToHost32(map->parent.ident) != 0) {
    // The parent is a regular reference to a resource.
    style->parent = Reference(android::util::DeviceToHost32(map->parent.ident));
  }

  for (const ResTable_map& map_entry : map) {
    if (Res_INTERNALID(android::util::DeviceToHost32(map_entry.name.ident))) {
      continue;
    }

    Style::Entry style_entry;
    style_entry.key = Reference(android::util::DeviceToHost32(map_entry.name.ident));
    style_entry.value = ParseValue(name, config, map_entry.value);
    if (!style_entry.value) {
      return {};
    }
    style->entries.push_back(std::move(style_entry));
  }
  return style;
}

std::unique_ptr<Attribute> BinaryResourceParser::ParseAttr(const ResourceNameRef& name,
                                                           const ConfigDescription& config,
                                                           const ResTable_map_entry* map) {
  std::unique_ptr<Attribute> attr = util::make_unique<Attribute>();
  attr->SetWeak((android::util::DeviceToHost16(map->flags) & ResTable_entry::FLAG_WEAK) != 0);

  // First we must discover what type of attribute this is. Find the type mask.
  auto type_mask_iter = std::find_if(begin(map), end(map), [](const ResTable_map& entry) -> bool {
    return android::util::DeviceToHost32(entry.name.ident) == ResTable_map::ATTR_TYPE;
  });

  if (type_mask_iter != end(map)) {
    attr->type_mask = android::util::DeviceToHost32(type_mask_iter->value.data);
  }

  for (const ResTable_map& map_entry : map) {
    if (Res_INTERNALID(android::util::DeviceToHost32(map_entry.name.ident))) {
      switch (android::util::DeviceToHost32(map_entry.name.ident)) {
        case ResTable_map::ATTR_MIN:
          attr->min_int = static_cast<int32_t>(map_entry.value.data);
          break;
        case ResTable_map::ATTR_MAX:
          attr->max_int = static_cast<int32_t>(map_entry.value.data);
          break;
      }
      continue;
    }

    if (attr->type_mask & (ResTable_map::TYPE_ENUM | ResTable_map::TYPE_FLAGS)) {
      Attribute::Symbol symbol;
      symbol.value = android::util::DeviceToHost32(map_entry.value.data);
      symbol.type = map_entry.value.dataType;
      symbol.symbol = Reference(android::util::DeviceToHost32(map_entry.name.ident));
      attr->symbols.push_back(std::move(symbol));
    }
  }

  // TODO(adamlesinski): Find i80n, attributes.
  return attr;
}

std::unique_ptr<Array> BinaryResourceParser::ParseArray(const ResourceNameRef& name,
                                                        const ConfigDescription& config,
                                                        const ResTable_map_entry* map) {
  std::unique_ptr<Array> array = util::make_unique<Array>();
  for (const ResTable_map& map_entry : map) {
    array->elements.push_back(ParseValue(name, config, map_entry.value));
  }
  return array;
}

std::unique_ptr<Plural> BinaryResourceParser::ParsePlural(const ResourceNameRef& name,
                                                          const ConfigDescription& config,
                                                          const ResTable_map_entry* map) {
  std::unique_ptr<Plural> plural = util::make_unique<Plural>();
  for (const ResTable_map& map_entry : map) {
    std::unique_ptr<Item> item = ParseValue(name, config, map_entry.value);
    if (!item) {
      return {};
    }

    switch (android::util::DeviceToHost32(map_entry.name.ident)) {
      case ResTable_map::ATTR_ZERO:
        plural->values[Plural::Zero] = std::move(item);
        break;
      case ResTable_map::ATTR_ONE:
        plural->values[Plural::One] = std::move(item);
        break;
      case ResTable_map::ATTR_TWO:
        plural->values[Plural::Two] = std::move(item);
        break;
      case ResTable_map::ATTR_FEW:
        plural->values[Plural::Few] = std::move(item);
        break;
      case ResTable_map::ATTR_MANY:
        plural->values[Plural::Many] = std::move(item);
        break;
      case ResTable_map::ATTR_OTHER:
        plural->values[Plural::Other] = std::move(item);
        break;
    }
  }
  return plural;
}

}  // namespace aapt
