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

#ifndef LOADEDARSC_H_
#define LOADEDARSC_H_

#include <memory>
#include <set>
#include <vector>

#include "android-base/macros.h"

#include "androidfw/ByteBucketArray.h"
#include "androidfw/Chunk.h"
#include "androidfw/Idmap.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/Util.h"

namespace android {

class DynamicPackageEntry {
 public:
  DynamicPackageEntry() = default;
  DynamicPackageEntry(std::string&& package_name, int package_id)
      : package_name(std::move(package_name)), package_id(package_id) {}

  std::string package_name;
  int package_id = 0;
};

// TypeSpec is going to be immediately proceeded by
// an array of Type structs, all in the same block of memory.
struct TypeSpec {
  // Pointer to the mmapped data where flags are kept.
  // Flags denote whether the resource entry is public
  // and under which configurations it varies.
  const ResTable_typeSpec* type_spec;

  // Pointer to the mmapped data where the IDMAP mappings for this type
  // exist. May be nullptr if no IDMAP exists.
  const IdmapEntry_header* idmap_entries;

  // The number of types that follow this struct.
  // There is a type for each configuration that entries are defined for.
  size_t type_count;

  // Trick to easily access a variable number of Type structs
  // proceeding this struct, and to ensure their alignment.
  const ResTable_type* types[0];

  inline uint32_t GetFlagsForEntryIndex(uint16_t entry_index) const {
    if (entry_index >= dtohl(type_spec->entryCount)) {
      return 0u;
    }

    const uint32_t* flags = reinterpret_cast<const uint32_t*>(type_spec + 1);
    return flags[entry_index];
  }
};

// TypeSpecPtr points to a block of memory that holds a TypeSpec struct, followed by an array of
// ResTable_type pointers.
// TypeSpecPtr is a managed pointer that knows how to delete itself.
using TypeSpecPtr = util::unique_cptr<TypeSpec>;

class LoadedPackage {
 public:
  static std::unique_ptr<const LoadedPackage> Load(const Chunk& chunk,
                                                   const LoadedIdmap* loaded_idmap, bool system,
                                                   bool load_as_shared_library);

  ~LoadedPackage();

  // Finds the entry with the specified type name and entry name. The names are in UTF-16 because
  // the underlying ResStringPool API expects this. For now this is acceptable, but since
  // the default policy in AAPT2 is to build UTF-8 string pools, this needs to change.
  // Returns a partial resource ID, with the package ID left as 0x00. The caller is responsible
  // for patching the correct package ID to the resource ID.
  uint32_t FindEntryByName(const std::u16string& type_name, const std::u16string& entry_name) const;

  static const ResTable_entry* GetEntry(const ResTable_type* type_chunk, uint16_t entry_index);

  static uint32_t GetEntryOffset(const ResTable_type* type_chunk, uint16_t entry_index);

  static const ResTable_entry* GetEntryFromOffset(const ResTable_type* type_chunk, uint32_t offset);

  // Returns the string pool where type names are stored.
  inline const ResStringPool* GetTypeStringPool() const {
    return &type_string_pool_;
  }

  // Returns the string pool where the names of resource entries are stored.
  inline const ResStringPool* GetKeyStringPool() const {
    return &key_string_pool_;
  }

  inline const std::string& GetPackageName() const {
    return package_name_;
  }

  inline int GetPackageId() const {
    return package_id_;
  }

  // Returns true if this package is dynamic (shared library) and needs to have an ID assigned.
  inline bool IsDynamic() const {
    return dynamic_;
  }

  // Returns true if this package originates from a system provided resource.
  inline bool IsSystem() const {
    return system_;
  }

  // Returns true if this package is from an overlay ApkAssets.
  inline bool IsOverlay() const {
    return overlay_;
  }

  // Returns the map of package name to package ID used in this LoadedPackage. At runtime, a
  // package could have been assigned a different package ID than what this LoadedPackage was
  // compiled with. AssetManager rewrites the package IDs so that they are compatible at runtime.
  inline const std::vector<DynamicPackageEntry>& GetDynamicPackageMap() const {
    return dynamic_package_map_;
  }

  // Populates a set of ResTable_config structs, possibly excluding configurations defined for
  // the mipmap type.
  void CollectConfigurations(bool exclude_mipmap, std::set<ResTable_config>* out_configs) const;

  // Populates a set of strings representing locales.
  // If `canonicalize` is set to true, each locale is transformed into its canonical format
  // before being inserted into the set. This may cause some equivalent locales to de-dupe.
  void CollectLocales(bool canonicalize, std::set<std::string>* out_locales) const;

  // type_idx is TT - 1 from 0xPPTTEEEE.
  inline const TypeSpec* GetTypeSpecByTypeIndex(uint8_t type_index) const {
    // If the type IDs are offset in this package, we need to take that into account when searching
    // for a type.
    return type_specs_[type_index - type_id_offset_].get();
  }

  template <typename Func>
  void ForEachTypeSpec(Func f) const {
    for (size_t i = 0; i < type_specs_.size(); i++) {
      const TypeSpecPtr& ptr = type_specs_[i];
      if (ptr != nullptr) {
        uint8_t type_id = ptr->type_spec->id;
        if (ptr->idmap_entries != nullptr) {
          type_id = ptr->idmap_entries->target_type_id;
        }
        f(ptr.get(), type_id - 1);
      }
    }
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(LoadedPackage);

  LoadedPackage();

  ResStringPool type_string_pool_;
  ResStringPool key_string_pool_;
  std::string package_name_;
  int package_id_ = -1;
  int type_id_offset_ = 0;
  bool dynamic_ = false;
  bool system_ = false;
  bool overlay_ = false;

  ByteBucketArray<TypeSpecPtr> type_specs_;
  std::vector<DynamicPackageEntry> dynamic_package_map_;
};

// Read-only view into a resource table. This class validates all data
// when loading, including offsets and lengths.
class LoadedArsc {
 public:
  // Load a resource table from memory pointed to by `data` of size `len`.
  // The lifetime of `data` must out-live the LoadedArsc returned from this method.
  // If `system` is set to true, the LoadedArsc is considered as a system provided resource.
  // If `load_as_shared_library` is set to true, the application package (0x7f) is treated
  // as a shared library (0x00). When loaded into an AssetManager, the package will be assigned an
  // ID.
  static std::unique_ptr<const LoadedArsc> Load(const StringPiece& data,
                                                const LoadedIdmap* loaded_idmap = nullptr,
                                                bool system = false,
                                                bool load_as_shared_library = false);

  // Create an empty LoadedArsc. This is used when an APK has no resources.arsc.
  static std::unique_ptr<const LoadedArsc> CreateEmpty();

  // Returns the string pool where all string resource values
  // (Res_value::dataType == Res_value::TYPE_STRING) are indexed.
  inline const ResStringPool* GetStringPool() const {
    return &global_string_pool_;
  }

  // Gets a pointer to the package with the specified package ID, or nullptr if no such package
  // exists.
  const LoadedPackage* GetPackageById(uint8_t package_id) const;

  // Returns a vector of LoadedPackage pointers, representing the packages in this LoadedArsc.
  inline const std::vector<std::unique_ptr<const LoadedPackage>>& GetPackages() const {
    return packages_;
  }

  // Returns true if this is a system provided resource.
  inline bool IsSystem() const {
    return system_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(LoadedArsc);

  LoadedArsc() = default;
  bool LoadTable(const Chunk& chunk, const LoadedIdmap* loaded_idmap, bool load_as_shared_library);

  ResStringPool global_string_pool_;
  std::vector<std::unique_ptr<const LoadedPackage>> packages_;
  bool system_ = false;
};

}  // namespace android

#endif /* LOADEDARSC_H_ */
