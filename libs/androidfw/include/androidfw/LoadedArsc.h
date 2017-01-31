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

  inline const ResStringPool* GetTypeStringPool() const { return &type_string_pool_; }

  inline const ResStringPool* GetKeyStringPool() const { return &key_string_pool_; }

  inline const std::string& GetPackageName() const { return package_name_; }

  inline int GetPackageId() const { return package_id_; }

  inline bool IsDynamic() const { return dynamic_; }

  inline const std::vector<DynamicPackageEntry>& GetDynamicPackageMap() const {
    return dynamic_package_map_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(LoadedPackage);

  static std::unique_ptr<LoadedPackage> Load(const Chunk& chunk);

  LoadedPackage() = default;

  ResStringPool type_string_pool_;
  ResStringPool key_string_pool_;
  std::string package_name_;
  int package_id_ = -1;
  bool dynamic_ = false;
  int type_id_offset_ = 0;

  ByteBucketArray<util::unique_cptr<TypeSpec>> type_specs_;
  std::vector<DynamicPackageEntry> dynamic_package_map_;
};

// Read-only view into a resource table. This class validates all data
// when loading, including offsets and lengths.
class LoadedArsc {
 public:
  // Load the resource table from memory. The data's lifetime must out-live the
  // object returned from this method.
  static std::unique_ptr<LoadedArsc> Load(const void* data, size_t len,
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

  inline const std::vector<std::unique_ptr<const LoadedPackage>>& GetPackages() const {
    return packages_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(LoadedArsc);

  LoadedArsc() = default;
  bool LoadTable(const Chunk& chunk, bool load_as_shared_library);

  ResStringPool global_string_pool_;
  std::vector<std::unique_ptr<const LoadedPackage>> packages_;
};

}  // namespace android

#endif /* LOADEDARSC_H_ */
