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

#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "utils/ByteOrder.h"
#include "utils/Trace.h"

#ifdef _WIN32
#ifdef ERROR
#undef ERROR
#endif
#endif

#include "androidfw/ByteBucketArray.h"
#include "androidfw/Chunk.h"
#include "androidfw/ResourceUtils.h"
#include "androidfw/Util.h"

using ::android::base::StringPrintf;

namespace android {

constexpr const static int kAppPackageId = 0x7f;

namespace {

// Builder that helps accumulate Type structs and then create a single
// contiguous block of memory to store both the TypeSpec struct and
// the Type structs.
class TypeSpecPtrBuilder {
 public:
  explicit TypeSpecPtrBuilder(const ResTable_typeSpec* header,
                              const IdmapEntry_header* idmap_header)
      : header_(header), idmap_header_(idmap_header) {
  }

  void AddType(const ResTable_type* type) {
    types_.push_back(type);
  }

  TypeSpecPtr Build() {
    // Check for overflow.
    using ElementType = const ResTable_type*;
    if ((std::numeric_limits<size_t>::max() - sizeof(TypeSpec)) / sizeof(ElementType) <
        types_.size()) {
      return {};
    }
    TypeSpec* type_spec =
        (TypeSpec*)::malloc(sizeof(TypeSpec) + (types_.size() * sizeof(ElementType)));
    type_spec->type_spec = header_;
    type_spec->idmap_entries = idmap_header_;
    type_spec->type_count = types_.size();
    memcpy(type_spec + 1, types_.data(), types_.size() * sizeof(ElementType));
    return TypeSpecPtr(type_spec);
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(TypeSpecPtrBuilder);

  const ResTable_typeSpec* header_;
  const IdmapEntry_header* idmap_header_;
  std::vector<const ResTable_type*> types_;
};

}  // namespace

LoadedPackage::LoadedPackage() = default;
LoadedPackage::~LoadedPackage() = default;

// Precondition: The header passed in has already been verified, so reading any fields and trusting
// the ResChunk_header is safe.
static bool VerifyResTableType(const ResTable_type* header) {
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
  const size_t offsets_length = sizeof(uint32_t) * entry_count;

  if (offsets_offset > entries_offset || entries_offset - offsets_offset < offsets_length) {
    LOG(ERROR) << "RES_TABLE_TYPE_TYPE entry offsets overlap actual entry data.";
    return false;
  }

  if (entries_offset > dtohl(header->header.size)) {
    LOG(ERROR) << "RES_TABLE_TYPE_TYPE entry offsets extend beyond chunk.";
    return false;
  }

  if (entries_offset & 0x03) {
    LOG(ERROR) << "RES_TABLE_TYPE_TYPE entries start at unaligned address.";
    return false;
  }
  return true;
}

static bool VerifyResTableEntry(const ResTable_type* type, uint32_t entry_offset) {
  // Check that the offset is aligned.
  if (entry_offset & 0x03) {
    LOG(ERROR) << "Entry at offset " << entry_offset << " is not 4-byte aligned.";
    return false;
  }

  // Check that the offset doesn't overflow.
  if (entry_offset > std::numeric_limits<uint32_t>::max() - dtohl(type->entriesStart)) {
    // Overflow in offset.
    LOG(ERROR) << "Entry at offset " << entry_offset << " is too large.";
    return false;
  }

  const size_t chunk_size = dtohl(type->header.size);

  entry_offset += dtohl(type->entriesStart);
  if (entry_offset > chunk_size - sizeof(ResTable_entry)) {
    LOG(ERROR) << "Entry at offset " << entry_offset
               << " is too large. No room for ResTable_entry.";
    return false;
  }

  const ResTable_entry* entry = reinterpret_cast<const ResTable_entry*>(
      reinterpret_cast<const uint8_t*>(type) + entry_offset);

  const size_t entry_size = dtohs(entry->size);
  if (entry_size < sizeof(*entry)) {
    LOG(ERROR) << "ResTable_entry size " << entry_size << " at offset " << entry_offset
               << " is too small.";
    return false;
  }

  if (entry_size > chunk_size || entry_offset > chunk_size - entry_size) {
    LOG(ERROR) << "ResTable_entry size " << entry_size << " at offset " << entry_offset
               << " is too large.";
    return false;
  }

  if (entry_size < sizeof(ResTable_map_entry)) {
    // There needs to be room for one Res_value struct.
    if (entry_offset + entry_size > chunk_size - sizeof(Res_value)) {
      LOG(ERROR) << "No room for Res_value after ResTable_entry at offset " << entry_offset
                 << " for type " << (int)type->id << ".";
      return false;
    }

    const Res_value* value =
        reinterpret_cast<const Res_value*>(reinterpret_cast<const uint8_t*>(entry) + entry_size);
    const size_t value_size = dtohs(value->size);
    if (value_size < sizeof(Res_value)) {
      LOG(ERROR) << "Res_value at offset " << entry_offset << " is too small.";
      return false;
    }

    if (value_size > chunk_size || entry_offset + entry_size > chunk_size - value_size) {
      LOG(ERROR) << "Res_value size " << value_size << " at offset " << entry_offset
                 << " is too large.";
      return false;
    }
  } else {
    const ResTable_map_entry* map = reinterpret_cast<const ResTable_map_entry*>(entry);
    const size_t map_entry_count = dtohl(map->count);
    size_t map_entries_start = entry_offset + entry_size;
    if (map_entries_start & 0x03) {
      LOG(ERROR) << "Map entries at offset " << entry_offset << " start at unaligned offset.";
      return false;
    }

    // Each entry is sizeof(ResTable_map) big.
    if (map_entry_count > ((chunk_size - map_entries_start) / sizeof(ResTable_map))) {
      LOG(ERROR) << "Too many map entries in ResTable_map_entry at offset " << entry_offset << ".";
      return false;
    }
  }
  return true;
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

const ResTable_entry* LoadedPackage::GetEntry(const ResTable_type* type_chunk,
                                              uint16_t entry_index) {
  uint32_t entry_offset = GetEntryOffset(type_chunk, entry_index);
  if (entry_offset == ResTable_type::NO_ENTRY) {
    return nullptr;
  }
  return GetEntryFromOffset(type_chunk, entry_offset);
}

uint32_t LoadedPackage::GetEntryOffset(const ResTable_type* type_chunk, uint16_t entry_index) {
  // The configuration matches and is better than the previous selection.
  // Find the entry value if it exists for this configuration.
  const size_t entry_count = dtohl(type_chunk->entryCount);
  const size_t offsets_offset = dtohs(type_chunk->header.headerSize);

  // Check if there is the desired entry in this type.

  if (type_chunk->flags & ResTable_type::FLAG_SPARSE) {
    // This is encoded as a sparse map, so perform a binary search.
    const ResTable_sparseTypeEntry* sparse_indices =
        reinterpret_cast<const ResTable_sparseTypeEntry*>(
            reinterpret_cast<const uint8_t*>(type_chunk) + offsets_offset);
    const ResTable_sparseTypeEntry* sparse_indices_end = sparse_indices + entry_count;
    const ResTable_sparseTypeEntry* result =
        std::lower_bound(sparse_indices, sparse_indices_end, entry_index,
                         [](const ResTable_sparseTypeEntry& entry, uint16_t entry_idx) {
                           return dtohs(entry.idx) < entry_idx;
                         });

    if (result == sparse_indices_end || dtohs(result->idx) != entry_index) {
      // No entry found.
      return ResTable_type::NO_ENTRY;
    }

    // Extract the offset from the entry. Each offset must be a multiple of 4 so we store it as
    // the real offset divided by 4.
    return uint32_t{dtohs(result->offset)} * 4u;
  }

  // This type is encoded as a dense array.
  if (entry_index >= entry_count) {
    // This entry cannot be here.
    return ResTable_type::NO_ENTRY;
  }

  const uint32_t* entry_offsets = reinterpret_cast<const uint32_t*>(
      reinterpret_cast<const uint8_t*>(type_chunk) + offsets_offset);
  return dtohl(entry_offsets[entry_index]);
}

const ResTable_entry* LoadedPackage::GetEntryFromOffset(const ResTable_type* type_chunk,
                                                        uint32_t offset) {
  if (UNLIKELY(!VerifyResTableEntry(type_chunk, offset))) {
    return nullptr;
  }
  return reinterpret_cast<const ResTable_entry*>(reinterpret_cast<const uint8_t*>(type_chunk) +
                                                 offset + dtohl(type_chunk->entriesStart));
}

void LoadedPackage::CollectConfigurations(bool exclude_mipmap,
                                          std::set<ResTable_config>* out_configs) const {
  const static std::u16string kMipMap = u"mipmap";
  const size_t type_count = type_specs_.size();
  for (size_t i = 0; i < type_count; i++) {
    const TypeSpecPtr& type_spec = type_specs_[i];
    if (type_spec != nullptr) {
      if (exclude_mipmap) {
        const int type_idx = type_spec->type_spec->id - 1;
        size_t type_name_len;
        const char16_t* type_name16 = type_string_pool_.stringAt(type_idx, &type_name_len);
        if (type_name16 != nullptr) {
          if (kMipMap.compare(0, std::u16string::npos, type_name16, type_name_len) == 0) {
            // This is a mipmap type, skip collection.
            continue;
          }
        }
        const char* type_name = type_string_pool_.string8At(type_idx, &type_name_len);
        if (type_name != nullptr) {
          if (strncmp(type_name, "mipmap", type_name_len) == 0) {
            // This is a mipmap type, skip collection.
            continue;
          }
        }
      }

      const auto iter_end = type_spec->types + type_spec->type_count;
      for (auto iter = type_spec->types; iter != iter_end; ++iter) {
        ResTable_config config;
        config.copyFromDtoH((*iter)->config);
        out_configs->insert(config);
      }
    }
  }
}

void LoadedPackage::CollectLocales(bool canonicalize, std::set<std::string>* out_locales) const {
  char temp_locale[RESTABLE_MAX_LOCALE_LEN];
  const size_t type_count = type_specs_.size();
  for (size_t i = 0; i < type_count; i++) {
    const TypeSpecPtr& type_spec = type_specs_[i];
    if (type_spec != nullptr) {
      const auto iter_end = type_spec->types + type_spec->type_count;
      for (auto iter = type_spec->types; iter != iter_end; ++iter) {
        ResTable_config configuration;
        configuration.copyFromDtoH((*iter)->config);
        if (configuration.locale != 0) {
          configuration.getBcp47Locale(temp_locale, canonicalize);
          std::string locale(temp_locale);
          out_locales->insert(std::move(locale));
        }
      }
    }
  }
}

uint32_t LoadedPackage::FindEntryByName(const std::u16string& type_name,
                                        const std::u16string& entry_name) const {
  ssize_t type_idx = type_string_pool_.indexOfString(type_name.data(), type_name.size());
  if (type_idx < 0) {
    return 0u;
  }

  ssize_t key_idx = key_string_pool_.indexOfString(entry_name.data(), entry_name.size());
  if (key_idx < 0) {
    return 0u;
  }

  const TypeSpec* type_spec = type_specs_[type_idx].get();
  if (type_spec == nullptr) {
    return 0u;
  }

  const auto iter_end = type_spec->types + type_spec->type_count;
  for (auto iter = type_spec->types; iter != iter_end; ++iter) {
    const ResTable_type* type = *iter;
    size_t entry_count = dtohl(type->entryCount);
    for (size_t entry_idx = 0; entry_idx < entry_count; entry_idx++) {
      const uint32_t* entry_offsets = reinterpret_cast<const uint32_t*>(
          reinterpret_cast<const uint8_t*>(type) + dtohs(type->header.headerSize));
      const uint32_t offset = dtohl(entry_offsets[entry_idx]);
      if (offset != ResTable_type::NO_ENTRY) {
        const ResTable_entry* entry = reinterpret_cast<const ResTable_entry*>(
            reinterpret_cast<const uint8_t*>(type) + dtohl(type->entriesStart) + offset);
        if (dtohl(entry->key.index) == static_cast<uint32_t>(key_idx)) {
          // The package ID will be overridden by the caller (due to runtime assignment of package
          // IDs for shared libraries).
          return make_resid(0x00, type_idx + type_id_offset_ + 1, entry_idx);
        }
      }
    }
  }
  return 0u;
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
                                                         const LoadedIdmap* loaded_idmap,
                                                         bool system, bool load_as_shared_library) {
  ATRACE_NAME("LoadedPackage::Load");
  std::unique_ptr<LoadedPackage> loaded_package(new LoadedPackage());

  // typeIdOffset was added at some point, but we still must recognize apps built before this
  // was added.
  constexpr size_t kMinPackageSize =
      sizeof(ResTable_package) - sizeof(ResTable_package::typeIdOffset);
  const ResTable_package* header = chunk.header<ResTable_package, kMinPackageSize>();
  if (header == nullptr) {
    LOG(ERROR) << "RES_TABLE_PACKAGE_TYPE too small.";
    return {};
  }

  loaded_package->system_ = system;

  loaded_package->package_id_ = dtohl(header->id);
  if (loaded_package->package_id_ == 0 ||
      (loaded_package->package_id_ == kAppPackageId && load_as_shared_library)) {
    // Package ID of 0 means this is a shared library.
    loaded_package->dynamic_ = true;
  }

  if (loaded_idmap != nullptr) {
    // This is an overlay and so it needs to pretend to be the target package.
    loaded_package->package_id_ = loaded_idmap->TargetPackageId();
    loaded_package->overlay_ = true;
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

  // A map of TypeSpec builders, each associated with an type index.
  // We use these to accumulate the set of Types available for a TypeSpec, and later build a single,
  // contiguous block of memory that holds all the Types together with the TypeSpec.
  std::unordered_map<int, std::unique_ptr<TypeSpecPtrBuilder>> type_builder_map;

  ChunkIterator iter(chunk.data_ptr(), chunk.data_size());
  while (iter.HasNext()) {
    const Chunk child_chunk = iter.Next();
    switch (child_chunk.type()) {
      case RES_STRING_POOL_TYPE: {
        const uintptr_t pool_address =
            reinterpret_cast<uintptr_t>(child_chunk.header<ResChunk_header>());
        const uintptr_t header_address = reinterpret_cast<uintptr_t>(header);
        if (pool_address == header_address + dtohl(header->typeStrings)) {
          // This string pool is the type string pool.
          status_t err = loaded_package->type_string_pool_.setTo(
              child_chunk.header<ResStringPool_header>(), child_chunk.size());
          if (err != NO_ERROR) {
            LOG(ERROR) << "RES_STRING_POOL_TYPE for types corrupt.";
            return {};
          }
        } else if (pool_address == header_address + dtohl(header->keyStrings)) {
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
        const ResTable_typeSpec* type_spec = child_chunk.header<ResTable_typeSpec>();
        if (type_spec == nullptr) {
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

        if (entry_count * sizeof(uint32_t) > chunk.data_size()) {
          LOG(ERROR) << "RES_TABLE_TYPE_SPEC_TYPE too small to hold entries.";
          return {};
        }

        // If this is an overlay, associate the mapping of this type to the target type
        // from the IDMAP.
        const IdmapEntry_header* idmap_entry_header = nullptr;
        if (loaded_idmap != nullptr) {
          idmap_entry_header = loaded_idmap->GetEntryMapForType(type_spec->id);
        }

        std::unique_ptr<TypeSpecPtrBuilder>& builder_ptr = type_builder_map[type_spec->id - 1];
        if (builder_ptr == nullptr) {
          builder_ptr = util::make_unique<TypeSpecPtrBuilder>(type_spec, idmap_entry_header);
          loaded_package->resource_ids_.set(type_spec->id, entry_count);
        } else {
          LOG(WARNING) << StringPrintf("RES_TABLE_TYPE_SPEC_TYPE already defined for ID %02x",
                                       type_spec->id);
        }
      } break;

      case RES_TABLE_TYPE_TYPE: {
        const ResTable_type* type = child_chunk.header<ResTable_type, kResTableTypeMinSize>();
        if (type == nullptr) {
          LOG(ERROR) << "RES_TABLE_TYPE_TYPE too small.";
          return {};
        }

        if (!VerifyResTableType(type)) {
          return {};
        }

        // Type chunks must be preceded by their TypeSpec chunks.
        std::unique_ptr<TypeSpecPtrBuilder>& builder_ptr = type_builder_map[type->id - 1];
        if (builder_ptr != nullptr) {
          builder_ptr->AddType(type);
        } else {
          LOG(ERROR) << StringPrintf(
              "RES_TABLE_TYPE_TYPE with ID %02x found without preceding RES_TABLE_TYPE_SPEC_TYPE.",
              type->id);
          return {};
        }
      } break;

      case RES_TABLE_LIBRARY_TYPE: {
        const ResTable_lib_header* lib = child_chunk.header<ResTable_lib_header>();
        if (lib == nullptr) {
          LOG(ERROR) << "RES_TABLE_LIBRARY_TYPE too small.";
          return {};
        }

        if (child_chunk.data_size() / sizeof(ResTable_lib_entry) < dtohl(lib->count)) {
          LOG(ERROR) << "RES_TABLE_LIBRARY_TYPE too small to hold entries.";
          return {};
        }

        loaded_package->dynamic_package_map_.reserve(dtohl(lib->count));

        const ResTable_lib_entry* const entry_begin =
            reinterpret_cast<const ResTable_lib_entry*>(child_chunk.data_ptr());
        const ResTable_lib_entry* const entry_end = entry_begin + dtohl(lib->count);
        for (auto entry_iter = entry_begin; entry_iter != entry_end; ++entry_iter) {
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
        const ResTable_overlayable_header* header =
            child_chunk.header<ResTable_overlayable_header>();
        if (header == nullptr) {
          LOG(ERROR) << "RES_TABLE_OVERLAYABLE_TYPE too small.";
          return {};
        }

        std::string name;
        util::ReadUtf16StringFromDevice(header->name, arraysize(header->name), &name);
        std::string actor;
        util::ReadUtf16StringFromDevice(header->actor, arraysize(header->actor), &actor);

        if (loaded_package->overlayable_map_.find(name) !=
            loaded_package->overlayable_map_.end()) {
          LOG(ERROR) << "Multiple <overlayable> blocks with the same name '" << name << "'.";
          return {};
        }
        loaded_package->overlayable_map_.emplace(name, actor);

        // Iterate over the overlayable policy chunks contained within the overlayable chunk data
        ChunkIterator overlayable_iter(child_chunk.data_ptr(), child_chunk.data_size());
        while (overlayable_iter.HasNext()) {
          const Chunk overlayable_child_chunk = overlayable_iter.Next();

          switch (overlayable_child_chunk.type()) {
            case RES_TABLE_OVERLAYABLE_POLICY_TYPE: {
              const ResTable_overlayable_policy_header* policy_header =
                  overlayable_child_chunk.header<ResTable_overlayable_policy_header>();
              if (policy_header == nullptr) {
                LOG(ERROR) << "RES_TABLE_OVERLAYABLE_POLICY_TYPE too small.";
                return {};
              }

              if ((overlayable_child_chunk.data_size() / sizeof(ResTable_ref))
                  < dtohl(policy_header->entry_count)) {
                LOG(ERROR) <<  "RES_TABLE_OVERLAYABLE_POLICY_TYPE too small to hold entries.";
                return {};
              }

              // Retrieve all the resource ids belonging to this policy chunk
              std::unordered_set<uint32_t> ids;
              const auto ids_begin =
                  reinterpret_cast<const ResTable_ref*>(overlayable_child_chunk.data_ptr());
              const auto ids_end = ids_begin + dtohl(policy_header->entry_count);
              for (auto id_iter = ids_begin; id_iter != ids_end; ++id_iter) {
                ids.insert(dtohl(id_iter->ident));
              }

              // Add the pairing of overlayable properties and resource ids to the package
              OverlayableInfo overlayable_info{};
              overlayable_info.name = name;
              overlayable_info.actor = actor;
              overlayable_info.policy_flags = policy_header->policy_flags;
              loaded_package->overlayable_infos_.push_back(std::make_pair(overlayable_info, ids));
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
    uint8_t type_idx = static_cast<uint8_t>(entry.first);
    TypeSpecPtr type_spec_ptr = entry.second->Build();
    if (type_spec_ptr == nullptr) {
      LOG(ERROR) << "Too many type configurations, overflow detected.";
      return {};
    }

    // We only add the type to the package if there is no IDMAP, or if the type is
    // overlaying something.
    if (loaded_idmap == nullptr || type_spec_ptr->idmap_entries != nullptr) {
      // If this is an overlay, insert it at the target type ID.
      if (type_spec_ptr->idmap_entries != nullptr) {
        type_idx = dtohs(type_spec_ptr->idmap_entries->target_type_id) - 1;
      }
      loaded_package->type_specs_.editItemAt(type_idx) = std::move(type_spec_ptr);
    }
  }

  return std::move(loaded_package);
}

bool LoadedArsc::LoadTable(const Chunk& chunk, const LoadedIdmap* loaded_idmap,
                           bool load_as_shared_library) {
  const ResTable_header* header = chunk.header<ResTable_header>();
  if (header == nullptr) {
    LOG(ERROR) << "RES_TABLE_TYPE too small.";
    return false;
  }

  const size_t package_count = dtohl(header->packageCount);
  size_t packages_seen = 0;

  packages_.reserve(package_count);

  ChunkIterator iter(chunk.data_ptr(), chunk.data_size());
  while (iter.HasNext()) {
    const Chunk child_chunk = iter.Next();
    switch (child_chunk.type()) {
      case RES_STRING_POOL_TYPE:
        // Only use the first string pool. Ignore others.
        if (global_string_pool_.getError() == NO_INIT) {
          status_t err = global_string_pool_.setTo(child_chunk.header<ResStringPool_header>(),
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
            LoadedPackage::Load(child_chunk, loaded_idmap, system_, load_as_shared_library);
        if (!loaded_package) {
          return false;
        }
        packages_.push_back(std::move(loaded_package));
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

std::unique_ptr<const LoadedArsc> LoadedArsc::Load(const StringPiece& data,
                                                   const LoadedIdmap* loaded_idmap, bool system,
                                                   bool load_as_shared_library) {
  ATRACE_NAME("LoadedArsc::LoadTable");

  // Not using make_unique because the constructor is private.
  std::unique_ptr<LoadedArsc> loaded_arsc(new LoadedArsc());
  loaded_arsc->system_ = system;

  ChunkIterator iter(data.data(), data.size());
  while (iter.HasNext()) {
    const Chunk chunk = iter.Next();
    switch (chunk.type()) {
      case RES_TABLE_TYPE:
        if (!loaded_arsc->LoadTable(chunk, loaded_idmap, load_as_shared_library)) {
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

  // Need to force a move for mingw32.
  return std::move(loaded_arsc);
}

std::unique_ptr<const LoadedArsc> LoadedArsc::CreateEmpty() {
  return std::unique_ptr<LoadedArsc>(new LoadedArsc());
}

}  // namespace android
