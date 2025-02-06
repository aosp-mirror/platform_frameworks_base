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

#include "format/binary/TableFlattener.h"

#include <limits>
#include <sstream>
#include <type_traits>
#include <variant>

#include "ResourceTable.h"
#include "ResourceValues.h"
#include "SdkConstants.h"
#include "android-base/logging.h"
#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "androidfw/BigBuffer.h"
#include "androidfw/ResourceUtils.h"
#include "format/binary/ChunkWriter.h"
#include "format/binary/ResEntryWriter.h"
#include "format/binary/ResourceTypeExtensions.h"
#include "optimize/Obfuscator.h"
#include "trace/TraceBuffer.h"

using namespace android;

namespace aapt {

namespace {

template <typename T>
static bool cmp_ids(const T* a, const T* b) {
  return a->id.value() < b->id.value();
}

static void strcpy16_htod(uint16_t* dst, size_t len, const StringPiece16& src) {
  if (len == 0) {
    return;
  }

  size_t i;
  const char16_t* src_data = src.data();
  for (i = 0; i < len - 1 && i < src.size(); i++) {
    dst[i] = android::util::HostToDevice16((uint16_t)src_data[i]);
  }
  dst[i] = 0;
}

struct OverlayableChunk {
  std::string actor;
  android::Source source;
  std::map<PolicyFlags, std::set<ResourceId>> policy_ids;
};

class PackageFlattener {
 public:
  PackageFlattener(IAaptContext* context, const ResourceTablePackageView& package,
                   const ResourceTable::ReferencedPackages* shared_libs,
                   SparseEntriesMode sparse_entries, bool compact_entries,
                   bool collapse_key_stringpool,
                   const std::set<ResourceName>& name_collapse_exemptions,
                   bool deduplicate_entry_values)
      : context_(context),
        diag_(context->GetDiagnostics()),
        package_(package),
        shared_libs_(shared_libs),
        sparse_entries_(sparse_entries),
        compact_entries_(compact_entries),
        collapse_key_stringpool_(collapse_key_stringpool),
        name_collapse_exemptions_(name_collapse_exemptions),
        deduplicate_entry_values_(deduplicate_entry_values) {
  }

  bool FlattenPackage(BigBuffer* buffer) {
    TRACE_CALL();
    ChunkWriter pkg_writer(buffer);
    ResTable_package* pkg_header = pkg_writer.StartChunk<ResTable_package>(RES_TABLE_PACKAGE_TYPE);
    pkg_header->id = android::util::HostToDevice32(package_.id.value());

    // AAPT truncated the package name, so do the same.
    // Shared libraries require full package names, so don't truncate theirs.
    if (context_->GetPackageType() != PackageType::kApp &&
        package_.name.size() >= arraysize(pkg_header->name)) {
      diag_->Error(android::DiagMessage()
                   << "package name '" << package_.name
                   << "' is too long. "
                      "Shared libraries cannot have truncated package names");
      return false;
    }

    // Copy the package name in device endianness.
    strcpy16_htod(pkg_header->name, arraysize(pkg_header->name),
                  android::util::Utf8ToUtf16(package_.name));

    // Serialize the types. We do this now so that our type and key strings
    // are populated. We write those first.
    android::BigBuffer type_buffer(1024);
    FlattenTypes(&type_buffer);

    pkg_header->typeStrings = android::util::HostToDevice32(pkg_writer.size());
    android::StringPool::FlattenUtf16(pkg_writer.buffer(), type_pool_, diag_);

    pkg_header->keyStrings = android::util::HostToDevice32(pkg_writer.size());
    android::StringPool::FlattenUtf8(pkg_writer.buffer(), key_pool_, diag_);

    // Append the types.
    buffer->AppendBuffer(std::move(type_buffer));

    // If there are libraries (or if the package ID is 0x00), encode a library chunk.
    if (package_.id.value() == 0x00 || !shared_libs_->empty()) {
      FlattenLibrarySpec(buffer);
    }

    if (!FlattenOverlayable(buffer)) {
      return false;
    }

    if (!FlattenAliases(buffer)) {
      return false;
    }

    pkg_writer.Finish();
    return true;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(PackageFlattener);

  // Use compact entries only if
  // 1) it is enabled, and that
  // 2) the entries will be accessed on platforms U+, and
  // 3) all entry keys can be encoded in 16 bits
  bool UseCompactEntries(const ConfigDescription& config, std::vector<FlatEntry>* entries) const {
    return compact_entries_ && context_->GetMinSdkVersion() > SDK_TIRAMISU &&
      std::none_of(entries->cbegin(), entries->cend(),
        [](const auto& e) { return e.entry_key >= std::numeric_limits<uint16_t>::max(); });
  }

  std::unique_ptr<ResEntryWriter> GetResEntryWriter(bool dedup, bool compact, BigBuffer* buffer) {
    if (dedup) {
      if (compact) {
        return std::make_unique<DeduplicateItemsResEntryWriter<true>>(buffer);
      } else {
        return std::make_unique<DeduplicateItemsResEntryWriter<false>>(buffer);
      }
    } else {
      if (compact) {
        return std::make_unique<SequentialResEntryWriter<true>>(buffer);
      } else {
        return std::make_unique<SequentialResEntryWriter<false>>(buffer);
      }
    }
  }

  bool FlattenConfig(const ResourceTableTypeView& type, const ConfigDescription& config,
                     const size_t num_total_entries, std::vector<FlatEntry>* entries,
                     BigBuffer* buffer) {
    CHECK(num_total_entries != 0);
    CHECK(num_total_entries <= std::numeric_limits<uint16_t>::max());

    ChunkWriter type_writer(buffer);
    ResTable_type* type_header = type_writer.StartChunk<ResTable_type>(RES_TABLE_TYPE_TYPE);
    type_header->id = type.id.value();
    type_header->config = config;
    type_header->config.swapHtoD();

    std::vector<uint32_t> offsets;
    offsets.resize(num_total_entries, 0xffffffffu);

    bool compact_entry = UseCompactEntries(config, entries);

    android::BigBuffer values_buffer(512);
    auto res_entry_writer = GetResEntryWriter(deduplicate_entry_values_,
                                              compact_entry, &values_buffer);

    for (FlatEntry& flat_entry : *entries) {
      CHECK(static_cast<size_t>(flat_entry.entry->id.value()) < num_total_entries);
      offsets[flat_entry.entry->id.value()] = res_entry_writer->Write(&flat_entry);
    }

    // whether the offsets can be represented in 2 bytes
    bool short_offsets = (values_buffer.size() / 4u) < std::numeric_limits<uint16_t>::max();

    bool sparse_encode = sparse_entries_ == SparseEntriesMode::Enabled ||
                         sparse_entries_ == SparseEntriesMode::Forced;

    if (sparse_entries_ == SparseEntriesMode::Forced ||
        (context_->GetMinSdkVersion() == 0 && config.sdkVersion == 0)) {
      // Sparse encode if forced or sdk version is not set in context and config.
    } else {
      // Otherwise, only sparse encode if the entries will be read on platforms S_V2+ (32).
      sparse_encode = sparse_encode && (context_->GetMinSdkVersion() >= SDK_S_V2);
    }

    // Only sparse encode if the offsets are representable in 2 bytes.
    sparse_encode = sparse_encode && short_offsets;

    // Only sparse encode if the ratio of populated entries to total entries is below some
    // threshold.
    sparse_encode =
        sparse_encode && ((100 * entries->size()) / num_total_entries) < kSparseEncodingThreshold;

    if (sparse_encode) {
      type_header->entryCount = android::util::HostToDevice32(entries->size());
      type_header->flags |= ResTable_type::FLAG_SPARSE;
      ResTable_sparseTypeEntry* indices =
          type_writer.NextBlock<ResTable_sparseTypeEntry>(entries->size());
      for (size_t i = 0; i < num_total_entries; i++) {
        if (offsets[i] != ResTable_type::NO_ENTRY) {
          CHECK((offsets[i] & 0x03) == 0);
          indices->idx = android::util::HostToDevice16(i);
          indices->offset = android::util::HostToDevice16(offsets[i] / 4u);
          indices++;
        }
      }
    } else {
      type_header->entryCount = android::util::HostToDevice32(num_total_entries);
      if (compact_entry && short_offsets) {
        // use 16-bit offset only when compact_entry is true
        type_header->flags |= ResTable_type::FLAG_OFFSET16;
        uint16_t* indices = type_writer.NextBlock<uint16_t>(num_total_entries);
        for (size_t i = 0; i < num_total_entries; i++) {
          indices[i] = android::util::HostToDevice16(offsets[i] / 4u);
        }
      } else {
        uint32_t* indices = type_writer.NextBlock<uint32_t>(num_total_entries);
        for (size_t i = 0; i < num_total_entries; i++) {
          indices[i] = android::util::HostToDevice32(offsets[i]);
        }
      }
    }

    type_writer.buffer()->Align4();
    type_header->entriesStart = android::util::HostToDevice32(type_writer.size());
    type_writer.buffer()->AppendBuffer(std::move(values_buffer));
    type_writer.Finish();
    return true;
  }

  bool FlattenAliases(BigBuffer* buffer) {
    if (aliases_.empty()) {
      return true;
    }

    ChunkWriter alias_writer(buffer);
    auto header =
        alias_writer.StartChunk<ResTable_staged_alias_header>(RES_TABLE_STAGED_ALIAS_TYPE);
    header->count = android::util::HostToDevice32(aliases_.size());

    auto mapping = alias_writer.NextBlock<ResTable_staged_alias_entry>(aliases_.size());
    for (auto& p : aliases_) {
      mapping->stagedResId = android::util::HostToDevice32(p.first);
      mapping->finalizedResId = android::util::HostToDevice32(p.second);
      ++mapping;
    }
    alias_writer.Finish();
    return true;
  }

  bool FlattenOverlayable(BigBuffer* buffer) {
    std::set<ResourceId> seen_ids;
    std::map<std::string, OverlayableChunk> overlayable_chunks;

    CHECK(bool(package_.id)) << "package must have an ID set when flattening <overlayable>";
    for (auto& type : package_.types) {
      CHECK(bool(type.id)) << "type must have an ID set when flattening <overlayable>";
      for (auto& entry : type.entries) {
        CHECK(bool(type.id)) << "entry must have an ID set when flattening <overlayable>";
        if (!entry.overlayable_item) {
          continue;
        }

        const OverlayableItem& item = entry.overlayable_item.value();

        // Resource ids should only appear once in the resource table
        ResourceId id = android::make_resid(package_.id.value(), type.id.value(), entry.id.value());
        CHECK(seen_ids.find(id) == seen_ids.end())
            << "multiple overlayable definitions found for resource "
            << ResourceName(package_.name, type.named_type, entry.name).to_string();
        seen_ids.insert(id);

        // Find the overlayable chunk with the specified name
        OverlayableChunk* overlayable_chunk = nullptr;
        auto iter = overlayable_chunks.find(item.overlayable->name);
        if (iter == overlayable_chunks.end()) {
          OverlayableChunk chunk{item.overlayable->actor, item.overlayable->source};
          overlayable_chunk =
              &overlayable_chunks.insert({item.overlayable->name, chunk}).first->second;
        } else {
          OverlayableChunk& chunk = iter->second;
          if (!(chunk.source == item.overlayable->source)) {
            // The name of an overlayable set of resources must be unique
            context_->GetDiagnostics()->Error(android::DiagMessage(item.overlayable->source)
                                              << "duplicate overlayable name"
                                              << item.overlayable->name << "'");
            context_->GetDiagnostics()->Error(android::DiagMessage(chunk.source)
                                              << "previous declaration here");
            return false;
          }

          CHECK(chunk.actor == item.overlayable->actor);
          overlayable_chunk = &chunk;
        }

        if (item.policies == 0) {
          context_->GetDiagnostics()->Error(android::DiagMessage(item.overlayable->source)
                                            << "overlayable " << entry.name
                                            << " does not specify policy");
          return false;
        }

        auto policy = overlayable_chunk->policy_ids.find(item.policies);
        if (policy != overlayable_chunk->policy_ids.end()) {
          policy->second.insert(id);
        } else {
          overlayable_chunk->policy_ids.insert(
              std::make_pair(item.policies, std::set<ResourceId>{id}));
        }
      }
    }

    for (auto& overlayable_pair : overlayable_chunks) {
      std::string name = overlayable_pair.first;
      OverlayableChunk& overlayable = overlayable_pair.second;

      // Write the header of the overlayable chunk
      ChunkWriter overlayable_writer(buffer);
      auto* overlayable_type =
          overlayable_writer.StartChunk<ResTable_overlayable_header>(RES_TABLE_OVERLAYABLE_TYPE);
      if (name.size() >= arraysize(overlayable_type->name)) {
        diag_->Error(android::DiagMessage()
                     << "overlayable name '" << name << "' exceeds maximum length ("
                     << arraysize(overlayable_type->name) << " utf16 characters)");
        return false;
      }
      strcpy16_htod(overlayable_type->name, arraysize(overlayable_type->name),
                    android::util::Utf8ToUtf16(name));

      if (overlayable.actor.size() >= arraysize(overlayable_type->actor)) {
        diag_->Error(android::DiagMessage()
                     << "overlayable name '" << overlayable.actor << "' exceeds maximum length ("
                     << arraysize(overlayable_type->actor) << " utf16 characters)");
        return false;
      }
      strcpy16_htod(overlayable_type->actor, arraysize(overlayable_type->actor),
                    android::util::Utf8ToUtf16(overlayable.actor));

      // Write each policy block for the overlayable
      for (auto& policy_ids : overlayable.policy_ids) {
        ChunkWriter policy_writer(buffer);
        auto* policy_type = policy_writer.StartChunk<ResTable_overlayable_policy_header>(
            RES_TABLE_OVERLAYABLE_POLICY_TYPE);
        policy_type->policy_flags = static_cast<PolicyFlags>(
            android::util::HostToDevice32(static_cast<uint32_t>(policy_ids.first)));
        policy_type->entry_count =
            android::util::HostToDevice32(static_cast<uint32_t>(policy_ids.second.size()));
        // Write the ids after the policy header
        auto* id_block = policy_writer.NextBlock<ResTable_ref>(policy_ids.second.size());
        for (const ResourceId& id : policy_ids.second) {
          id_block->ident = android::util::HostToDevice32(id.id);
          id_block++;
        }
        policy_writer.Finish();
      }
      overlayable_writer.Finish();
    }

    return true;
  }

  ResTable_typeSpec* FlattenTypeSpec(const ResourceTableTypeView& type,
                                     const std::vector<ResourceTableEntryView>& sorted_entries,
                                     BigBuffer* buffer) {
    ChunkWriter type_spec_writer(buffer);
    ResTable_typeSpec* spec_header =
        type_spec_writer.StartChunk<ResTable_typeSpec>(RES_TABLE_TYPE_SPEC_TYPE);
    spec_header->id = type.id.value();

    if (sorted_entries.empty()) {
      type_spec_writer.Finish();
      return spec_header;
    }

    // We can't just take the size of the vector. There may be holes in the
    // entry ID space.
    // Since the entries are sorted by ID, the last one will be the biggest.
    const size_t num_entries = sorted_entries.back().id.value() + 1;

    spec_header->entryCount = android::util::HostToDevice32(num_entries);

    // Reserve space for the masks of each resource in this type. These
    // show for which configuration axis the resource changes.
    uint32_t* config_masks = type_spec_writer.NextBlock<uint32_t>(num_entries);

    for (const ResourceTableEntryView& entry : sorted_entries) {
      const uint16_t entry_id = entry.id.value();

      // Populate the config masks for this entry.
      uint32_t& entry_config_masks = config_masks[entry_id];
      if (entry.visibility.level == Visibility::Level::kPublic) {
        entry_config_masks |= android::util::HostToDevice32(ResTable_typeSpec::SPEC_PUBLIC);
      }
      if (entry.visibility.staged_api) {
        entry_config_masks |= android::util::HostToDevice32(ResTable_typeSpec::SPEC_STAGED_API);
      }

      const size_t config_count = entry.values.size();
      for (size_t i = 0; i < config_count; i++) {
        const ConfigDescription& config = entry.values[i]->config;
        for (size_t j = i + 1; j < config_count; j++) {
          config_masks[entry_id] |=
              android::util::HostToDevice32(config.diff(entry.values[j]->config));
        }
      }
    }
    type_spec_writer.Finish();
    return spec_header;
  }

  bool FlattenTypes(BigBuffer* buffer) {
    size_t expected_type_id = 1;
    for (const ResourceTableTypeView& type : package_.types) {
      if (type.named_type.type == ResourceType::kStyleable ||
          type.named_type.type == ResourceType::kMacro) {
        // Styleables and macros are not real resource types.
        continue;
      }

      // If there is a gap in the type IDs, fill in the StringPool
      // with empty values until we reach the ID we expect.
      while (type.id.value() > expected_type_id) {
        std::stringstream type_name;
        type_name << "?" << expected_type_id;
        type_pool_.MakeRef(type_name.str());
        expected_type_id++;
      }
      expected_type_id++;
      type_pool_.MakeRef(type.named_type.to_string());

      const auto type_spec_header = FlattenTypeSpec(type, type.entries, buffer);
      if (!type_spec_header) {
        return false;
      }

      // Since the entries are sorted by ID, the last ID will be the largest.
      const size_t num_entries = type.entries.back().id.value() + 1;

      // The binary resource table lists resource entries for each
      // configuration.
      // We store them inverted, where a resource entry lists the values for
      // each
      // configuration available. Here we reverse this to match the binary
      // table.
      std::map<ConfigDescription, std::vector<FlatEntry>> config_to_entry_list_map;

      for (const ResourceTableEntryView& entry : type.entries) {
        if (entry.staged_id) {
          aliases_.insert(std::make_pair(
              entry.staged_id.value().id.id,
              ResourceId(package_.id.value(), type.id.value(), entry.id.value()).id));
        }

        uint32_t local_key_index;
        auto onObfuscate = [this, &local_key_index, &entry](Obfuscator::Result obfuscatedResult,
                                                            const ResourceName& resource_name) {
          if (obfuscatedResult == Obfuscator::Result::Keep_ExemptionList) {
            local_key_index = (uint32_t)key_pool_.MakeRef(entry.name).index();
          } else if (obfuscatedResult == Obfuscator::Result::Keep_Overlayable) {
            // if the resource name of the specific entry is obfuscated and this
            // entry is in the overlayable list, the overlay can't work on this
            // overlayable at runtime because the name has been obfuscated in
            // resources.arsc during flatten operation.
            const OverlayableItem& item = entry.overlayable_item.value();
            context_->GetDiagnostics()->Warn(android::DiagMessage(item.overlayable->source)
                                             << "The resource name of overlayable entry '"
                                             << resource_name.to_string()
                                             << "' shouldn't be obfuscated in resources.arsc");

            local_key_index = (uint32_t)key_pool_.MakeRef(entry.name).index();
          } else {
            local_key_index =
                (uint32_t)key_pool_.MakeRef(Obfuscator::kObfuscatedResourceName).index();
          }
        };

        Obfuscator::ObfuscateResourceName(collapse_key_stringpool_, name_collapse_exemptions_,
                                          type.named_type, entry, onObfuscate);

        // Group values by configuration.
        for (auto& config_value : entry.values) {
          config_to_entry_list_map[config_value->config].push_back(
              FlatEntry{&entry, config_value->value.get(), local_key_index});
        }
      }

      // Flatten a configuration value.
      for (auto& entry : config_to_entry_list_map) {
        if (!FlattenConfig(type, entry.first, num_entries, &entry.second, buffer)) {
          return false;
        }
      }

      // And now we can update the type entries count in the typeSpec header.
      type_spec_header->typesCount = android::util::HostToDevice16(uint16_t(std::min<uint32_t>(
          config_to_entry_list_map.size(), std::numeric_limits<uint16_t>::max())));
    }
    return true;
  }

  void FlattenLibrarySpec(BigBuffer* buffer) {
    ChunkWriter lib_writer(buffer);
    ResTable_lib_header* lib_header =
        lib_writer.StartChunk<ResTable_lib_header>(RES_TABLE_LIBRARY_TYPE);

    const size_t num_entries = (package_.id.value() == 0x00 ? 1 : 0) + shared_libs_->size();
    CHECK(num_entries > 0);

    lib_header->count = android::util::HostToDevice32(num_entries);

    ResTable_lib_entry* lib_entry = buffer->NextBlock<ResTable_lib_entry>(num_entries);
    if (package_.id.value() == 0x00) {
      // Add this package
      lib_entry->packageId = android::util::HostToDevice32(0x00);
      strcpy16_htod(lib_entry->packageName, arraysize(lib_entry->packageName),
                    android::util::Utf8ToUtf16(package_.name));
      ++lib_entry;
    }

    for (auto& map_entry : *shared_libs_) {
      lib_entry->packageId = android::util::HostToDevice32(map_entry.first);
      strcpy16_htod(lib_entry->packageName, arraysize(lib_entry->packageName),
                    android::util::Utf8ToUtf16(map_entry.second));
      ++lib_entry;
    }
    lib_writer.Finish();
  }

  IAaptContext* context_;
  android::IDiagnostics* diag_;
  const ResourceTablePackageView package_;
  const ResourceTable::ReferencedPackages* shared_libs_;
  SparseEntriesMode sparse_entries_;
  bool compact_entries_;
  android::StringPool type_pool_;
  android::StringPool key_pool_;
  bool collapse_key_stringpool_;
  const std::set<ResourceName>& name_collapse_exemptions_;
  std::map<uint32_t, uint32_t> aliases_;
  bool deduplicate_entry_values_;
};

}  // namespace

bool TableFlattener::Consume(IAaptContext* context, ResourceTable* table) {
  TRACE_CALL();
  // We must do this before writing the resources, since the string pool IDs may change.
  table->string_pool.Prune();
  table->string_pool.Sort(
      [](const android::StringPool::Context& a, const android::StringPool::Context& b) -> int {
        int diff = util::compare(a.priority, b.priority);
        if (diff == 0) {
          diff = a.config.compare(b.config);
        }
        return diff;
      });

  // Write the ResTable header.
  const auto& table_view =
      table->GetPartitionedView(ResourceTableViewOptions{.create_alias_entries = true});
  ChunkWriter table_writer(buffer_);
  ResTable_header* table_header = table_writer.StartChunk<ResTable_header>(RES_TABLE_TYPE);
  table_header->packageCount = android::util::HostToDevice32(table_view.packages.size());

  // Flatten the values string pool.
  android::StringPool::FlattenUtf8(table_writer.buffer(), table->string_pool,
                                   context->GetDiagnostics());

  android::BigBuffer package_buffer(1024);

  // Flatten each package.
  for (auto& package : table_view.packages) {
    if (context->GetPackageType() == PackageType::kApp) {
      // Write a self mapping entry for this package if the ID is non-standard (0x7f).
      CHECK((bool)package.id) << "Resource ids have not been assigned before flattening the table";
      const uint8_t package_id = package.id.value();
      if (package_id != kFrameworkPackageId && package_id != kAppPackageId) {
        auto result = table->included_packages_.insert({package_id, package.name});
        if (!result.second && result.first->second != package.name) {
          // A mapping for this package ID already exists, and is a different package. Error!
          context->GetDiagnostics()->Error(
              android::DiagMessage() << android::base::StringPrintf(
                  "can't map package ID %02x to '%s'. Already mapped to '%s'", package_id,
                  package.name.c_str(), result.first->second.c_str()));
          return false;
        }
      }
    }

    PackageFlattener flattener(context, package, &table->included_packages_,
                               options_.sparse_entries,
                               options_.use_compact_entries,
                               options_.collapse_key_stringpool,
                               options_.name_collapse_exemptions,
                               options_.deduplicate_entry_values);
    if (!flattener.FlattenPackage(&package_buffer)) {
      return false;
    }
  }

  // Finally merge all the packages into the main buffer.
  table_writer.buffer()->AppendBuffer(std::move(package_buffer));
  table_writer.Finish();
  return true;
}

}  // namespace aapt
