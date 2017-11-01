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

struct LoadedArscEntry {
  // A pointer to the resource table entry for this resource.
  // If the size of the entry is > sizeof(ResTable_entry), it can be cast to
  // a ResTable_map_entry and processed as a bag/map.
  const ResTable_entry* entry = nullptr;

  // The dynamic package ID map for the package from which this resource came from.
  const DynamicRefTable* dynamic_ref_table = nullptr;

  // The string pool reference to the type's name. This uses a different string pool than
  // the global string pool, but this is hidden from the caller.
  StringPoolRef type_string_ref;

  // The string pool reference to the entry's name. This uses a different string pool than
  // the global string pool, but this is hidden from the caller.
  StringPoolRef entry_string_ref;
};

struct TypeSpec;
class LoadedArsc;

class LoadedPackage {
  friend class LoadedArsc;

 public:
  bool FindEntry(uint8_t type_idx, uint16_t entry_idx, const ResTable_config& config,
                 LoadedArscEntry* out_entry, ResTable_config* out_selected_config,
                 uint32_t* out_flags) const;

  // Returns the string pool where type names are stored.
  inline const ResStringPool* GetTypeStringPool() const { return &type_string_pool_; }

  // Returns the string pool where the names of resource entries are stored.
  inline const ResStringPool* GetKeyStringPool() const { return &key_string_pool_; }

  inline const std::string& GetPackageName() const { return package_name_; }

  inline int GetPackageId() const { return package_id_; }

  // Returns true if this package is dynamic (shared library) and needs to have an ID assigned.
  inline bool IsDynamic() const { return dynamic_; }

  // Returns true if this package originates from a system provided resource.
  inline bool IsSystem() const { return system_; }

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

  // Finds the entry with the specified type name and entry name. The names are in UTF-16 because
  // the underlying ResStringPool API expects this. For now this is acceptable, but since
  // the default policy in AAPT2 is to build UTF-8 string pools, this needs to change.
  // Returns a partial resource ID, with the package ID left as 0x00. The caller is responsible
  // for patching the correct package ID to the resource ID.
  uint32_t FindEntryByName(const std::u16string& type_name, const std::u16string& entry_name) const;

 private:
  DISALLOW_COPY_AND_ASSIGN(LoadedPackage);

  static std::unique_ptr<LoadedPackage> Load(const Chunk& chunk);

  LoadedPackage() = default;

  ResStringPool type_string_pool_;
  ResStringPool key_string_pool_;
  std::string package_name_;
  int package_id_ = -1;
  int type_id_offset_ = 0;
  bool dynamic_ = false;
  bool system_ = false;

  ByteBucketArray<util::unique_cptr<TypeSpec>> type_specs_;
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
  static std::unique_ptr<const LoadedArsc> Load(const void* data, size_t len, bool system = false,
                                                bool load_as_shared_library = false);

  ~LoadedArsc();

  // Returns the string pool where all string resource values
  // (Res_value::dataType == Res_value::TYPE_STRING) are indexed.
  inline const ResStringPool* GetStringPool() const { return &global_string_pool_; }

  // Finds the resource with ID `resid` with the best value for configuration `config`.
  // The parameter `out_entry` will be filled with the resulting resource entry.
  // The resource entry can be a simple entry (ResTable_entry) or a complex bag
  // (ResTable_entry_map).
  bool FindEntry(uint32_t resid, const ResTable_config& config, LoadedArscEntry* out_entry,
                 ResTable_config* selected_config, uint32_t* out_flags) const;

  // Gets a pointer to the name of the package in `resid`, or nullptr if the package doesn't exist.
  const LoadedPackage* GetPackageForId(uint32_t resid) const;

  // Returns true if this is a system provided resource.
  inline bool IsSystem() const { return system_; }

  // Returns a vector of LoadedPackage pointers, representing the packages in this LoadedArsc.
  inline const std::vector<std::unique_ptr<const LoadedPackage>>& GetPackages() const {
    return packages_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(LoadedArsc);

  LoadedArsc() = default;
  bool LoadTable(const Chunk& chunk, bool load_as_shared_library);

  ResStringPool global_string_pool_;
  std::vector<std::unique_ptr<const LoadedPackage>> packages_;
  bool system_ = false;
};

}  // namespace android

#endif /* LOADEDARSC_H_ */
