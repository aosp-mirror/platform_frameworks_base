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

#define ATRACE_TAG ATRACE_TAG_RESOURCES

#include "androidfw/LoadedArsc.h"

#include <algorithm>
#include <cstddef>
#include <limits>
#include <optional>

#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "utils/ByteOrder.h"
#include "utils/Trace.h"

#ifdef _WIN32
#ifdef ERROR
#undef ERROR
#endif
#endif

#include "androidfw/Chunk.h"
#include "androidfw/ResourceUtils.h"
#include "androidfw/Util.h"

using android::base::StringPrintf;

namespace android {

constexpr const static int kFrameworkPackageId = 0x01;
constexpr const static int kAppPackageId = 0x7f;

namespace {

// Builder that helps accumulate Type structs and then create a single
// contiguous block of memory to store both the TypeSpec struct and
// the Type structs.
struct TypeSpecBuilder {
  explicit TypeSpecBuilder(incfs::verified_map_ptr<ResTable_typeSpec> header) : header_(header) {
    type_entries.reserve(dtohs(header_->typesCount));
  }

  void AddType(incfs::verified_map_ptr<ResTable_type> type) {
    TypeSpec::TypeEntry& entry = type_entries.emplace_back();
    entry.config.copyFromDtoH(type->config);
    entry.type = type;
  }

  TypeSpec Build() {
    type_entries.shrink_to_fit();
    return {header_, std::move(type_entries)};
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(TypeSpecBuilder);

  incfs::verified_map_ptr<ResTable_typeSpec> header_;
  std::vector<TypeSpec::TypeEntry> type_entries;
};

}  // namespace

// Precondition: The header passed in has already been verified, so reading any fields and trusting
// the ResChunk_header is safe.
static bool VerifyResTableType(incfs::map_ptr<ResTable_type> header) {
  if (header->id == 0) {
    LOG(ERROR) << "RES_TABLE_TYPE_TYPE has invalid ID 0.";
    return false;
  }

  const size_t entry_count = dtohl(header->entryCount);
  if (entry_count > std::numeric_limits<uint16_t>::max()) {
    LOG(ERROR) << "RES_TABLE_TYPE_TYPE has too many entries (" << entry_count << ").";
    return false;
  }

  // Make sure that there is enough room for the entry offsets.
  const size_t offsets_offset = dtohs(header->header.headerSize);
  const size_t entries_offset = dtohl(header->entriesStart);
  const size_t offsets_length = header->flags & ResTable_type::FLAG_OFFSET16
                                    ? sizeof(uint16_t) * entry_count
                                    : sizeof(uint32_t) * entry_count;

  if (offsets_offset > entries_offset || entries_offset - offsets_offset < offsets_length) {
    LOG(ERROR) << "RES_TABLE_TYPE_TYPE entry offsets overlap actual entry data.";
    return false;
  }

  if (entries_offset > dtohl(header->header.size)) {
    LOG(ERROR) << "RES_TABLE_TYPE_TYPE entry offsets extend beyond chunk.";
    return false;
  }

  if (entries_offset & 0x03U) {
    LOG(ERROR) << "RES_TABLE_TYPE_TYPE entries start at unaligned address.";
    return false;
  }
  return true;
}

static base::expected<incfs::verified_map_ptr<ResTable_entry>, NullOrIOError>
VerifyResTableEntry(incfs::verified_map_ptr<ResTable_type> type, uint32_t entry_offset) {
  // Check that the offset is aligned.
  if (UNLIKELY(entry_offset & 0x03U)) {
    LOG(ERROR) << "Entry at offset " << entry_offset << " is not 4-byte aligned.";
    return base::unexpected(std::nullopt);
  }

  // Check that the offset doesn't overflow.
  if (UNLIKELY(entry_offset > std::numeric_limits<uint32_t>::max() - dtohl(type->entriesStart))) {
    // Overflow in offset.
    LOG(ERROR) << "Entry at offset " << entry_offset << " is too large.";
    return base::unexpected(std::nullopt);
  }

  const size_t chunk_size = dtohl(type->header.size);

  entry_offset += dtohl(type->entriesStart);
  if (UNLIKELY(entry_offset > chunk_size - sizeof(ResTable_entry))) {
    LOG(ERROR) << "Entry at offset " << entry_offset
               << " is too large. No room for ResTable_entry.";
    return base::unexpected(std::nullopt);
  }

  auto entry = type.offset(entry_offset).convert<ResTable_entry>();
  if (UNLIKELY(!entry)) {
    return base::unexpected(IOError::PAGES_MISSING);
  }

  const size_t entry_size = entry->size();
  if (UNLIKELY(entry_size < sizeof(entry.value()))) {
    LOG(ERROR) << "ResTable_entry size " << entry_size << " at offset " << entry_offset
               << " is too small.";
    return base::unexpected(std::nullopt);
  }

  if (UNLIKELY(entry_size > chunk_size || entry_offset > chunk_size - entry_size)) {
    LOG(ERROR) << "ResTable_entry size " << entry_size << " at offset " << entry_offset
               << " is too large.";
    return base::unexpected(std::nullopt);
  }

  // If entry is compact, value is already encoded, and a compact entry
  // cannot be a map_entry, we are done verifying
  if (entry->is_compact())
    return entry.verified();

  if (entry_size < sizeof(ResTable_map_entry)) {
    // There needs to be room for one Res_value struct.
    if (UNLIKELY(entry_offset + entry_size > chunk_size - sizeof(Res_value))) {
      LOG(ERROR) << "No room for Res_value after ResTable_entry at offset " << entry_offset
                 << " for type " << (int)type->id << ".";
      return base::unexpected(std::nullopt);
    }

    auto value = entry.offset(entry_size).convert<Res_value>();
    if (UNLIKELY(!value)) {
       return base::unexpected(IOError::PAGES_MISSING);
    }

    const size_t value_size = dtohs(value->size);
    if (UNLIKELY(value_size < sizeof(Res_value))) {
      LOG(ERROR) << "Res_value at offset " << entry_offset << " is too small.";
      return base::unexpected(std::nullopt);
    }

    if (UNLIKELY(value_size > chunk_size || entry_offset + entry_size > chunk_size - value_size)) {
      LOG(ERROR) << "Res_value size " << value_size << " at offset " << entry_offset
                 << " is too large.";
      return base::unexpected(std::nullopt);
    }
  } else {
    auto map = entry.convert<ResTable_map_entry>();
    if (UNLIKELY(!map)) {
      return base::unexpected(IOError::PAGES_MISSING);
    }

    const size_t map_entry_count = dtohl(map->count);
    size_t map_entries_start = entry_offset + entry_size;
    if (UNLIKELY(map_entries_start & 0x03U)) {
      LOG(ERROR) << "Map entries at offset " << entry_offset << " start at unaligned offset.";
      return base::unexpected(std::nullopt);
    }

    // Each entry is sizeof(ResTable_map) big.
    if (UNLIKELY(map_entry_count > ((chunk_size - map_entries_start) / sizeof(ResTable_map)))) {
      LOG(ERROR) << "Too many map entries in ResTable_map_entry at offset " << entry_offset << ".";
      return base::unexpected(std::nullopt);
    }
  }
  return entry.verified();
}

LoadedPackage::iterator::iterator(const LoadedPackage* lp, size_t ti, size_t ei)
    : loadedPackage_(lp),
      typeIndex_(ti),
      entryIndex_(ei),
      typeIndexEnd_(lp->resource_ids_.size() + 1) {
  while (typeIndex_ < typeIndexEnd_ && loadedPackage_->resource_ids_[typeIndex_] == 0) {
    typeIndex_++;
  }
}

LoadedPackage::iterator& LoadedPackage::iterator::operator++() {
  while (typeIndex_ < typeIndexEnd_) {
    if (entryIndex_ + 1 < loadedPackage_->resource_ids_[typeIndex_]) {
      entryIndex_++;
      break;
    }
    entryIndex_ = 0;
    typeIndex_++;
    if (typeIndex_ < typeIndexEnd_ && loadedPackage_->resource_ids_[typeIndex_] != 0) {
      break;
    }
  }
  return *this;
}

uint32_t LoadedPackage::iterator::operator*() const {
  if (typeIndex_ >= typeIndexEnd_) {
    return 0;
  }
  return make_resid(loadedPackage_->package_id_, typeIndex_ + loadedPackage_->type_id_offset_,
          entryIndex_);
}

base::expected<incfs::verified_map_ptr<ResTable_entry>, NullOrIOError> LoadedPackage::GetEntry(
    incfs::verified_map_ptr<ResTable_type> type_chunk, uint16_t entry_index) {
  base::expected<uint32_t, NullOrIOError> entry_offset = GetEntryOffset(type_chunk, entry_index);
  if (UNLIKELY(!entry_offset.has_value())) {
    return base::unexpected(entry_offset.error());
  }
  return GetEntryFromOffset(type_chunk, entry_offset.value());
}

base::expected<uint32_t, NullOrIOError> LoadedPackage::GetEntryOffset(
    incfs::verified_map_ptr<ResTable_type> type_chunk, uint16_t entry_index) {
  // The configuration matches and is better than the previous selection.
  // Find the entry value if it exists for this configuration.
  const size_t entry_count = dtohl(type_chunk->entryCount);
  const auto offsets = type_chunk.offset(dtohs(type_chunk->header.headerSize));

  // Check if there is the desired entry in this type.
  if (type_chunk->flags & ResTable_type::FLAG_SPARSE) {
    // This is encoded as a sparse map, so perform a binary search.
    bool error = false;
    auto sparse_indices = offsets.convert<ResTable_sparseTypeEntry>().iterator();
    auto sparse_indices_end = sparse_indices + entry_count;
    auto result = std::lower_bound(sparse_indices, sparse_indices_end, entry_index,
                                   [&error](const incfs::map_ptr<ResTable_sparseTypeEntry>& entry,
                                            uint16_t entry_idx) {
      if (UNLIKELY(!entry)) {
        return error = true;
      }
      return dtohs(entry->idx) < entry_idx;
    });

    if (result == sparse_indices_end) {
      // No entry found.
      return base::unexpected(std::nullopt);
    }

    const incfs::verified_map_ptr<ResTable_sparseTypeEntry> entry = (*result).verified();
    if (dtohs(entry->idx) != entry_index) {
      if (error) {
        return base::unexpected(IOError::PAGES_MISSING);
      }
      return base::unexpected(std::nullopt);
    }

    // Extract the offset from the entry. Each offset must be a multiple of 4 so we store it as
    // the real offset divided by 4.
    return uint32_t{dtohs(entry->offset)} * 4u;
  }

  // This type is encoded as a dense array.
  if (entry_index >= entry_count) {
    // This entry cannot be here.
    return base::unexpected(std::nullopt);
  }

  uint32_t result;

  if (type_chunk->flags & ResTable_type::FLAG_OFFSET16) {
    const auto entry_offset_ptr = offsets.convert<uint16_t>() + entry_index;
    if (UNLIKELY(!entry_offset_ptr)) {
      return base::unexpected(IOError::PAGES_MISSING);
    }
    result = offset_from16(entry_offset_ptr.value());
  } else {
    const auto entry_offset_ptr = offsets.convert<uint32_t>() + entry_index;
    if (UNLIKELY(!entry_offset_ptr)) {
      return base::unexpected(IOError::PAGES_MISSING);
    }
    result = dtohl(entry_offset_ptr.value());
  }

  if (result == ResTable_type::NO_ENTRY) {
    return base::unexpected(std::nullopt);
  }
  return result;
}

base::expected<incfs::verified_map_ptr<ResTable_entry>, NullOrIOError>
LoadedPackage::GetEntryFromOffset(incfs::verified_map_ptr<ResTable_type> type_chunk,
                                  uint32_t offset) {
  auto valid = VerifyResTableEntry(type_chunk, offset);
  if (UNLIKELY(!valid.has_value())) {
    return base::unexpected(valid.error());
  }
  return valid;
}

base::expected<std::monostate, IOError> LoadedPackage::CollectConfigurations(
    bool exclude_mipmap, std::set<ResTable_config>* out_configs) const {
  for (const auto& type_spec : type_specs_) {
    if (exclude_mipmap) {
      const int type_idx = type_spec.first - 1;
      const auto type_name16 = type_string_pool_.stringAt(type_idx);
      if (UNLIKELY(IsIOError(type_name16))) {
        return base::unexpected(GetIOError(type_name16.error()));
      }
      if (type_name16.has_value()) {
        if (strncmp16(type_name16->data(), u"mipmap", type_name16->size()) == 0) {
          // This is a mipmap type, skip collection.
          continue;
        }
      }

      const auto type_name = type_string_pool_.string8At(type_idx);
      if (UNLIKELY(IsIOError(type_name))) {
        return base::unexpected(GetIOError(type_name.error()));
      }
      if (type_name.has_value()) {
        if (strncmp(type_name->data(), "mipmap", type_name->size()) == 0) {
          // This is a mipmap type, skip collection.
          continue;
        }
      }
    }

    for (const auto& type_entry : type_spec.second.type_entries) {
      out_configs->insert(type_entry.config);
    }
  }
  return {};
}

void LoadedPackage::CollectLocales(bool canonicalize, std::set<std::string>* out_locales) const {
  char temp_locale[RESTABLE_MAX_LOCALE_LEN];
  for (const auto& type_spec : type_specs_) {
    for (const auto& type_entry : type_spec.second.type_entries) {
      if (type_entry.config.locale != 0) {
        type_entry.config.getBcp47Locale(temp_locale, canonicalize);
        std::string locale(temp_locale);
        out_locales->insert(std::move(locale));
      }
    }
  }
}

base::expected<uint32_t, NullOrIOError> LoadedPackage::FindEntryByName(
    const std::u16string& type_name, const std::u16string& entry_name) const {
  const base::expected<size_t, NullOrIOError> type_idx = type_string_pool_.indexOfString(
      type_name.data(), type_name.size());
  if (!type_idx.has_value()) {
    return base::unexpected(type_idx.error());
  }

  const base::expected<size_t, NullOrIOError> key_idx = key_string_pool_.indexOfString(
      entry_name.data(), entry_name.size());
  if (!key_idx.has_value()) {
    return base::unexpected(key_idx.error());
  }

  const TypeSpec* type_spec = GetTypeSpecByTypeIndex(*type_idx);
  if (type_spec == nullptr) {
    return base::unexpected(std::nullopt);
  }

  for (const auto& type_entry : type_spec->type_entries) {
    const incfs::verified_map_ptr<ResTable_type>& type = type_entry.type;

    const size_t entry_count = dtohl(type->entryCount);
    const auto entry_offsets = type.offset(dtohs(type->header.headerSize));

    for (size_t entry_idx = 0; entry_idx < entry_count; entry_idx++) {
      uint32_t offset;
      uint16_t res_idx;
      if (type->flags & ResTable_type::FLAG_SPARSE) {
        auto sparse_entry = entry_offsets.convert<ResTable_sparseTypeEntry>() + entry_idx;
        if (!sparse_entry) {
          return base::unexpected(IOError::PAGES_MISSING);
        }
        offset = dtohs(sparse_entry->offset) * 4u;
        res_idx  = dtohs(sparse_entry->idx);
      } else if (type->flags & ResTable_type::FLAG_OFFSET16) {
        auto entry = entry_offsets.convert<uint16_t>() + entry_idx;
        if (!entry) {
          return base::unexpected(IOError::PAGES_MISSING);
        }
        offset = offset_from16(entry.value());
        res_idx = entry_idx;
      } else {
        auto entry = entry_offsets.convert<uint32_t>() + entry_idx;
        if (!entry) {
          return base::unexpected(IOError::PAGES_MISSING);
        }
        offset = dtohl(entry.value());
        res_idx = entry_idx;
      }

      if (offset != ResTable_type::NO_ENTRY) {
        auto entry = type.offset(dtohl(type->entriesStart) + offset).convert<ResTable_entry>();
        if (!entry) {
          return base::unexpected(IOError::PAGES_MISSING);
        }

        if (entry->key() == static_cast<uint32_t>(*key_idx)) {
          // The package ID will be overridden by the caller (due to runtime assignment of package
          // IDs for shared libraries).
          return make_resid(0x00, *type_idx + type_id_offset_ + 1, res_idx);
        }
      }
    }
  }
  return base::unexpected(std::nullopt);
}

const LoadedPackage* LoadedArsc::GetPackageById(uint8_t package_id) const {
  for (const auto& loaded_package : packages_) {
    if (loaded_package->GetPackageId() == package_id) {
      return loaded_package.get();
    }
  }
  return nullptr;
}

std::unique_ptr<const LoadedPackage> LoadedPackage::Load(const Chunk& chunk,
                                                         package_property_t property_flags) {
  ATRACE_NAME("LoadedPackage::Load");
  const bool optimize_name_lookups = (property_flags & PROPERTY_OPTIMIZE_NAME_LOOKUPS) != 0;
  std::unique_ptr<LoadedPackage> loaded_package(new LoadedPackage(optimize_name_lookups));

  // typeIdOffset was added at some point, but we still must recognize apps built before this
  // was added.
  constexpr size_t kMinPackageSize =
      sizeof(ResTable_package) - sizeof(ResTable_package::typeIdOffset);
  const incfs::map_ptr<ResTable_package> header = chunk.header<ResTable_package, kMinPackageSize>();
  if (!header) {
    LOG(ERROR) << "RES_TABLE_PACKAGE_TYPE too small.";
    return {};
  }

  if ((property_flags & PROPERTY_SYSTEM) != 0) {
    loaded_package->property_flags_ |= PROPERTY_SYSTEM;
  }

  if ((property_flags & PROPERTY_LOADER) != 0) {
    loaded_package->property_flags_ |= PROPERTY_LOADER;
  }

  if ((property_flags & PROPERTY_OVERLAY) != 0) {
    // Overlay resources must have an exclusive resource id space for referencing internal
    // resources.
    loaded_package->property_flags_ |= PROPERTY_OVERLAY | PROPERTY_DYNAMIC;
  }

  loaded_package->package_id_ = dtohl(header->id);
  if (loaded_package->package_id_ == 0 ||
      (loaded_package->package_id_ == kAppPackageId && (property_flags & PROPERTY_DYNAMIC) != 0)) {
    loaded_package->property_flags_ |= PROPERTY_DYNAMIC;
  }

  if (header->header.headerSize >= sizeof(ResTable_package)) {
    uint32_t type_id_offset = dtohl(header->typeIdOffset);
    if (type_id_offset > std::numeric_limits<uint8_t>::max()) {
      LOG(ERROR) << "RES_TABLE_PACKAGE_TYPE type ID offset too large.";
      return {};
    }
    loaded_package->type_id_offset_ = static_cast<int>(type_id_offset);
  }

  util::ReadUtf16StringFromDevice(header->name, arraysize(header->name),
                                  &loaded_package->package_name_);

  const bool only_overlayable = (property_flags & PROPERTY_ONLY_OVERLAYABLES) != 0;

  // A map of TypeSpec builders, each associated with an type index.
  // We use these to accumulate the set of Types available for a TypeSpec, and later build a single,
  // contiguous block of memory that holds all the Types together with the TypeSpec.
  std::unordered_map<int, std::optional<TypeSpecBuilder>> type_builder_map;

  ChunkIterator iter(chunk.data_ptr(), chunk.data_size());
  while (iter.HasNext()) {
    const Chunk child_chunk = iter.Next();
    if (only_overlayable && child_chunk.type() != RES_TABLE_OVERLAYABLE_TYPE) {
      continue;
    }
    switch (child_chunk.type()) {
      case RES_STRING_POOL_TYPE: {
        const auto pool_address = child_chunk.header<ResChunk_header>();
        if (!pool_address) {
          LOG(ERROR) << "RES_STRING_POOL_TYPE is incomplete due to incremental installation.";
          return {};
        }

        if (pool_address == header.offset(dtohl(header->typeStrings)).convert<ResChunk_header>()) {
          // This string pool is the type string pool.
          status_t err = loaded_package->type_string_pool_.setTo(
              child_chunk.header<ResStringPool_header>(), child_chunk.size());
          if (err != NO_ERROR) {
            LOG(ERROR) << "RES_STRING_POOL_TYPE for types corrupt.";
            return {};
          }
        } else if (pool_address == header.offset(dtohl(header->keyStrings))
                                         .convert<ResChunk_header>()) {
          // This string pool is the key string pool.
          status_t err = loaded_package->key_string_pool_.setTo(
              child_chunk.header<ResStringPool_header>(), child_chunk.size());
          if (err != NO_ERROR) {
            LOG(ERROR) << "RES_STRING_POOL_TYPE for keys corrupt.";
            return {};
          }
        } else {
          LOG(WARNING) << "Too many RES_STRING_POOL_TYPEs found in RES_TABLE_PACKAGE_TYPE.";
        }
      } break;

      case RES_TABLE_TYPE_SPEC_TYPE: {
        const auto type_spec = child_chunk.header<ResTable_typeSpec>();
        if (!type_spec) {
          LOG(ERROR) << "RES_TABLE_TYPE_SPEC_TYPE too small.";
          return {};
        }

        if (type_spec->id == 0) {
          LOG(ERROR) << "RES_TABLE_TYPE_SPEC_TYPE has invalid ID 0.";
          return {};
        }

        if (loaded_package->type_id_offset_ + static_cast<int>(type_spec->id) >
            std::numeric_limits<uint8_t>::max()) {
          LOG(ERROR) << "RES_TABLE_TYPE_SPEC_TYPE has out of range ID.";
          return {};
        }

        // The data portion of this chunk contains entry_count 32bit entries,
        // each one representing a set of flags.
        // Here we only validate that the chunk is well formed.
        const size_t entry_count = dtohl(type_spec->entryCount);

        // There can only be 2^16 entries in a type, because that is the ID
        // space for entries (EEEE) in the resource ID 0xPPTTEEEE.
        if (entry_count > std::numeric_limits<uint16_t>::max()) {
          LOG(ERROR) << "RES_TABLE_TYPE_SPEC_TYPE has too many entries (" << entry_count << ").";
          return {};
        }

        if (entry_count * sizeof(uint32_t) > child_chunk.data_size()) {
          LOG(ERROR) << "RES_TABLE_TYPE_SPEC_TYPE too small to hold entries.";
          return {};
        }

        auto& maybe_type_builder = type_builder_map[type_spec->id];
        if (!maybe_type_builder) {
          maybe_type_builder.emplace(type_spec.verified());
          loaded_package->resource_ids_.set(type_spec->id, entry_count);
        } else {
          LOG(WARNING) << StringPrintf("RES_TABLE_TYPE_SPEC_TYPE already defined for ID %02x",
                                       type_spec->id);
        }
      } break;

      case RES_TABLE_TYPE_TYPE: {
        const auto type = child_chunk.header<ResTable_type, kResTableTypeMinSize>();
        if (!type) {
          LOG(ERROR) << "RES_TABLE_TYPE_TYPE too small.";
          return {};
        }

        if (!VerifyResTableType(type)) {
          return {};
        }

        // Type chunks must be preceded by their TypeSpec chunks.
        auto& maybe_type_builder = type_builder_map[type->id];
        if (maybe_type_builder) {
          maybe_type_builder->AddType(type.verified());
        } else {
          LOG(ERROR) << StringPrintf(
              "RES_TABLE_TYPE_TYPE with ID %02x found without preceding RES_TABLE_TYPE_SPEC_TYPE.",
              type->id);
          return {};
        }
      } break;

      case RES_TABLE_LIBRARY_TYPE: {
        const auto lib = child_chunk.header<ResTable_lib_header>();
        if (!lib) {
          LOG(ERROR) << "RES_TABLE_LIBRARY_TYPE too small.";
          return {};
        }

        if (child_chunk.data_size() / sizeof(ResTable_lib_entry) < dtohl(lib->count)) {
          LOG(ERROR) << "RES_TABLE_LIBRARY_TYPE too small to hold entries.";
          return {};
        }

        loaded_package->dynamic_package_map_.reserve(dtohl(lib->count));

        const auto entry_begin = child_chunk.data_ptr().convert<ResTable_lib_entry>();
        const auto entry_end = entry_begin + dtohl(lib->count);
        for (auto entry_iter = entry_begin; entry_iter != entry_end; ++entry_iter) {
          if (!entry_iter) {
            return {};
          }

          std::string package_name;
          util::ReadUtf16StringFromDevice(entry_iter->packageName,
                                          arraysize(entry_iter->packageName), &package_name);

          if (dtohl(entry_iter->packageId) >= std::numeric_limits<uint8_t>::max()) {
            LOG(ERROR) << StringPrintf(
                "Package ID %02x in RES_TABLE_LIBRARY_TYPE too large for package '%s'.",
                dtohl(entry_iter->packageId), package_name.c_str());
            return {};
          }

          loaded_package->dynamic_package_map_.emplace_back(std::move(package_name),
                                                            dtohl(entry_iter->packageId));
        }
      } break;

      case RES_TABLE_OVERLAYABLE_TYPE: {
        const auto overlayable = child_chunk.header<ResTable_overlayable_header>();
        if (!overlayable) {
          LOG(ERROR) << "RES_TABLE_OVERLAYABLE_TYPE too small.";
          return {};
        }

        std::string name;
        util::ReadUtf16StringFromDevice(overlayable->name, std::size(overlayable->name), &name);
        std::string actor;
        util::ReadUtf16StringFromDevice(overlayable->actor, std::size(overlayable->actor), &actor);
        auto [name_to_actor_it, inserted] =
            loaded_package->overlayable_map_.emplace(std::move(name), std::move(actor));
        if (!inserted) {
          LOG(ERROR) << "Multiple <overlayable> blocks with the same name '"
                     << name_to_actor_it->first << "'.";
          return {};
        }
        if (only_overlayable) {
          break;
        }

        // Iterate over the overlayable policy chunks contained within the overlayable chunk data
        ChunkIterator overlayable_iter(child_chunk.data_ptr(), child_chunk.data_size());
        while (overlayable_iter.HasNext()) {
          const Chunk overlayable_child_chunk = overlayable_iter.Next();

          switch (overlayable_child_chunk.type()) {
            case RES_TABLE_OVERLAYABLE_POLICY_TYPE: {
              const auto policy_header =
                  overlayable_child_chunk.header<ResTable_overlayable_policy_header>();
              if (!policy_header) {
                LOG(ERROR) << "RES_TABLE_OVERLAYABLE_POLICY_TYPE too small.";
                return {};
              }
              if ((overlayable_child_chunk.data_size() / sizeof(ResTable_ref))
                  < dtohl(policy_header->entry_count)) {
                LOG(ERROR) <<  "RES_TABLE_OVERLAYABLE_POLICY_TYPE too small to hold entries.";
                return {};
              }

              // Retrieve all the resource ids belonging to this policy chunk
              const auto ids_begin = overlayable_child_chunk.data_ptr().convert<ResTable_ref>();
              const auto ids_end = ids_begin + dtohl(policy_header->entry_count);
              std::unordered_set<uint32_t> ids;
              ids.reserve(ids_end - ids_begin);
              for (auto id_iter = ids_begin; id_iter != ids_end; ++id_iter) {
                if (!id_iter) {
                  LOG(ERROR) << "NULL ResTable_ref record??";
                  return {};
                }
                ids.insert(dtohl(id_iter->ident));
              }

              // Add the pairing of overlayable properties and resource ids to the package
              OverlayableInfo overlayable_info {
                .name = name_to_actor_it->first,
                .actor = name_to_actor_it->second,
                .policy_flags = policy_header->policy_flags
              };
              loaded_package->overlayable_infos_.emplace_back(std::move(overlayable_info), std::move(ids));
              loaded_package->defines_overlayable_ = true;
              break;
            }

            default:
              LOG(WARNING) << StringPrintf("Unknown chunk type '%02x'.", chunk.type());
              break;
          }
        }

        if (overlayable_iter.HadError()) {
          LOG(ERROR) << StringPrintf("Error parsing RES_TABLE_OVERLAYABLE_TYPE: %s",
                                     overlayable_iter.GetLastError().c_str());
          if (overlayable_iter.HadFatalError()) {
            return {};
          }
        }
      } break;

      case RES_TABLE_STAGED_ALIAS_TYPE: {
        if (loaded_package->package_id_ != kFrameworkPackageId) {
          LOG(WARNING) << "Alias chunk ignored for non-framework package '"
                       << loaded_package->package_name_ << "'";
          break;
        }

        const auto lib_alias = child_chunk.header<ResTable_staged_alias_header>();
        if (!lib_alias) {
          LOG(ERROR) << "RES_TABLE_STAGED_ALIAS_TYPE is too small.";
          return {};
        }
        if ((child_chunk.data_size() / sizeof(ResTable_staged_alias_entry))
            < dtohl(lib_alias->count)) {
          LOG(ERROR) << "RES_TABLE_STAGED_ALIAS_TYPE is too small to hold entries.";
          return {};
        }
        const auto entry_begin = child_chunk.data_ptr().convert<ResTable_staged_alias_entry>();
        const auto entry_end = entry_begin + dtohl(lib_alias->count);
        std::unordered_set<uint32_t> finalized_ids;
        finalized_ids.reserve(entry_end - entry_begin);
        loaded_package->alias_id_map_.reserve(entry_end - entry_begin);
        for (auto entry_iter = entry_begin; entry_iter != entry_end; ++entry_iter) {
          if (!entry_iter) {
            LOG(ERROR) << "NULL ResTable_staged_alias_entry record??";
            return {};
          }
          auto finalized_id = dtohl(entry_iter->finalizedResId);
          if (!finalized_ids.insert(finalized_id).second) {
            LOG(ERROR) << StringPrintf("Repeated finalized resource id '%08x' in staged aliases.",
                                       finalized_id);
            return {};
          }

          auto staged_id = dtohl(entry_iter->stagedResId);
          loaded_package->alias_id_map_.emplace_back(staged_id, finalized_id);
        }

        std::sort(loaded_package->alias_id_map_.begin(), loaded_package->alias_id_map_.end(),
            [](auto&& l, auto&& r) { return l.first < r.first; });
        const auto duplicate_it =
            std::adjacent_find(loaded_package->alias_id_map_.begin(),
                               loaded_package->alias_id_map_.end(),
                               [](auto&& l, auto&& r) { return l.first == r.first; });
          if (duplicate_it != loaded_package->alias_id_map_.end()) {
            LOG(ERROR) << StringPrintf("Repeated staged resource id '%08x' in staged aliases.",
                                       duplicate_it->first);
            return {};
          }
      } break;

      default:
        LOG(WARNING) << StringPrintf("Unknown chunk type '%02x'.", chunk.type());
        break;
    }
  }

  if (iter.HadError()) {
    LOG(ERROR) << iter.GetLastError();
    if (iter.HadFatalError()) {
      return {};
    }
  }

  // Flatten and construct the TypeSpecs.
  for (auto& entry : type_builder_map) {
    TypeSpec type_spec = entry.second->Build();
    uint8_t type_id = static_cast<uint8_t>(entry.first);
    loaded_package->type_specs_[type_id] = std::move(type_spec);
  }

  return std::move(loaded_package);
}

bool LoadedArsc::LoadTable(const Chunk& chunk, const LoadedIdmap* loaded_idmap,
                           package_property_t property_flags) {
  incfs::map_ptr<ResTable_header> header = chunk.header<ResTable_header>();
  if (!header) {
    LOG(ERROR) << "RES_TABLE_TYPE too small.";
    return false;
  }

  if (loaded_idmap != nullptr) {
    global_string_pool_ = util::make_unique<OverlayStringPool>(loaded_idmap);
  }

  const bool only_overlayable = (property_flags & PROPERTY_ONLY_OVERLAYABLES) != 0;

  const size_t package_count = dtohl(header->packageCount);
  size_t packages_seen = 0;

  if (!only_overlayable) {
    packages_.reserve(package_count);
  }

  ChunkIterator iter(chunk.data_ptr(), chunk.data_size());
  while (iter.HasNext()) {
    const Chunk child_chunk = iter.Next();
    if (only_overlayable && child_chunk.type() != RES_TABLE_PACKAGE_TYPE) {
      continue;
    }
    switch (child_chunk.type()) {
      case RES_STRING_POOL_TYPE:
        // Only use the first string pool. Ignore others.
        if (global_string_pool_->getError() == NO_INIT) {
          status_t err = global_string_pool_->setTo(child_chunk.header<ResStringPool_header>(),
                                                    child_chunk.size());
          if (err != NO_ERROR) {
            LOG(ERROR) << "RES_STRING_POOL_TYPE corrupt.";
            return false;
          }
        } else {
          LOG(WARNING) << "Multiple RES_STRING_POOL_TYPEs found in RES_TABLE_TYPE.";
        }
        break;

      case RES_TABLE_PACKAGE_TYPE: {
        if (packages_seen + 1 > package_count) {
          LOG(ERROR) << "More package chunks were found than the " << package_count
                     << " declared in the header.";
          return false;
        }
        packages_seen++;

        std::unique_ptr<const LoadedPackage> loaded_package =
            LoadedPackage::Load(child_chunk, property_flags);
        if (!loaded_package) {
          return false;
        }
        packages_.push_back(std::move(loaded_package));
        if (only_overlayable) {
          // Overlayable is always in the first package, no need to process anything else.
          return true;
        }
      } break;

      default:
        LOG(WARNING) << StringPrintf("Unknown chunk type '%02x'.", chunk.type());
        break;
    }
  }

  if (iter.HadError()) {
    LOG(ERROR) << iter.GetLastError();
    if (iter.HadFatalError()) {
      return false;
    }
  }
  return true;
}

bool LoadedArsc::LoadStringPool(const LoadedIdmap* loaded_idmap) {
  if (loaded_idmap != nullptr) {
    global_string_pool_ = util::make_unique<OverlayStringPool>(loaded_idmap);
  }
  return true;
}

std::unique_ptr<LoadedArsc> LoadedArsc::Load(incfs::map_ptr<void> data,
                                             const size_t length,
                                             const LoadedIdmap* loaded_idmap,
                                             const package_property_t property_flags) {
  ATRACE_NAME("LoadedArsc::Load");

  // Not using make_unique because the constructor is private.
  std::unique_ptr<LoadedArsc> loaded_arsc(new LoadedArsc());

  ChunkIterator iter(data, length);
  while (iter.HasNext()) {
    const Chunk chunk = iter.Next();
    switch (chunk.type()) {
      case RES_TABLE_TYPE:
        if (!loaded_arsc->LoadTable(chunk, loaded_idmap, property_flags)) {
          return {};
        }
        break;

      default:
        LOG(WARNING) << StringPrintf("Unknown chunk type '%02x'.", chunk.type());
        break;
    }
  }

  if (iter.HadError()) {
    LOG(ERROR) << iter.GetLastError();
    if (iter.HadFatalError()) {
      return {};
    }
  }

  return loaded_arsc;
}

std::unique_ptr<LoadedArsc> LoadedArsc::Load(const LoadedIdmap* loaded_idmap) {
  ATRACE_NAME("LoadedArsc::Load");

  // Not using make_unique because the constructor is private.
  std::unique_ptr<LoadedArsc> loaded_arsc(new LoadedArsc());
  loaded_arsc->LoadStringPool(loaded_idmap);
  return loaded_arsc;
}


std::unique_ptr<LoadedArsc> LoadedArsc::CreateEmpty() {
  return std::unique_ptr<LoadedArsc>(new LoadedArsc());
}

}  // namespace android
