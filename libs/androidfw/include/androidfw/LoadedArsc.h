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

#include <map>
#include <memory>
#include <set>
#include <vector>
#include <unordered_map>
#include <unordered_set>

#include <android-base/macros.h>
#include <android-base/result.h>

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
  struct TypeEntry {
    incfs::verified_map_ptr<ResTable_type> type;

    // Type configurations are accessed frequently when setting up an AssetManager and querying
    // resources. Access this cached configuration to minimize page faults.
    ResTable_config config;
  };

  // Pointer to the mmapped data where flags are kept. Flags denote whether the resource entry is
  // public and under which configurations it varies.
  incfs::verified_map_ptr<ResTable_typeSpec> type_spec;

  std::vector<TypeEntry> type_entries;

  base::expected<uint32_t, NullOrIOError> GetFlagsForEntryIndex(uint16_t entry_index) const {
    if (entry_index >= dtohl(type_spec->entryCount)) {
      return 0U;
    }
    const auto entry_flags_ptr = ((type_spec + 1).convert<uint32_t>() + entry_index);
    if (!entry_flags_ptr) {
      return base::unexpected(IOError::PAGES_MISSING);
    }
    return entry_flags_ptr.value();
  }
};

// Flags that change the behavior of loaded packages.
// Keep in sync with f/b/android/content/res/ApkAssets.java
using package_property_t = uint32_t;
enum : package_property_t {
  // The package contains framework resource values specified by the system.
  // This allows some functions to filter out this package when computing
  // what configurations/resources are available.
  PROPERTY_SYSTEM = 1U << 0U,

  // The package is a shared library or has a package id of 7f and is loaded as a shared library by
  // force.
  PROPERTY_DYNAMIC = 1U << 1U,

  // The package has been loaded dynamically using a ResourcesProvider.
  PROPERTY_LOADER = 1U << 2U,

  // The package is a RRO.
  PROPERTY_OVERLAY = 1U << 3U,

  // The apk assets is owned by the application running in this process and incremental crash
  // protections for this APK must be disabled.
  PROPERTY_DISABLE_INCREMENTAL_HARDENING = 1U << 4U,
};

struct OverlayableInfo {
  std::string name;
  std::string actor;
  uint32_t policy_flags;
};

class LoadedPackage {
 public:
  class iterator {
   public:
    iterator& operator=(const iterator& rhs) {
      loadedPackage_ = rhs.loadedPackage_;
      typeIndex_ = rhs.typeIndex_;
      entryIndex_ = rhs.entryIndex_;
      return *this;
    }

    bool operator==(const iterator& rhs) const {
      return loadedPackage_ == rhs.loadedPackage_ &&
             typeIndex_ == rhs.typeIndex_ &&
             entryIndex_ == rhs.entryIndex_;
    }

    bool operator!=(const iterator& rhs) const {
      return !(*this == rhs);
    }

    iterator operator++(int) {
      size_t prevTypeIndex_ = typeIndex_;
      size_t prevEntryIndex_ = entryIndex_;
      operator++();
      return iterator(loadedPackage_, prevTypeIndex_, prevEntryIndex_);
    }

    iterator& operator++();

    uint32_t operator*() const;

   private:
    friend class LoadedPackage;

    iterator(const LoadedPackage* lp, size_t ti, size_t ei);

    const LoadedPackage* loadedPackage_;
    size_t typeIndex_;
    size_t entryIndex_;
    const size_t typeIndexEnd_;  // STL style end, so one past the last element
  };

  iterator begin() const {
    return iterator(this, 0, 0);
  }

  iterator end() const {
    return iterator(this, resource_ids_.size() + 1, 0);
  }

  static std::unique_ptr<const LoadedPackage> Load(const Chunk& chunk,
                                                   package_property_t property_flags);

  // Finds the entry with the specified type name and entry name. The names are in UTF-16 because
  // the underlying ResStringPool API expects this. For now this is acceptable, but since
  // the default policy in AAPT2 is to build UTF-8 string pools, this needs to change.
  // Returns a partial resource ID, with the package ID left as 0x00. The caller is responsible
  // for patching the correct package ID to the resource ID.
  base::expected<uint32_t, NullOrIOError> FindEntryByName(const std::u16string& type_name,
                                                          const std::u16string& entry_name) const;

  static base::expected<incfs::map_ptr<ResTable_entry>, NullOrIOError> GetEntry(
      incfs::verified_map_ptr<ResTable_type> type_chunk, uint16_t entry_index);

  static base::expected<uint32_t, NullOrIOError> GetEntryOffset(
      incfs::verified_map_ptr<ResTable_type> type_chunk, uint16_t entry_index);

  static base::expected<incfs::map_ptr<ResTable_entry>, NullOrIOError> GetEntryFromOffset(
      incfs::verified_map_ptr<ResTable_type> type_chunk, uint32_t offset);

  // Returns the string pool where type names are stored.
  const ResStringPool* GetTypeStringPool() const {
    return &type_string_pool_;
  }

  // Returns the string pool where the names of resource entries are stored.
  const ResStringPool* GetKeyStringPool() const {
    return &key_string_pool_;
  }

  const std::string& GetPackageName() const {
    return package_name_;
  }

  int GetPackageId() const {
    return package_id_;
  }

  // Returns true if this package is dynamic (shared library) and needs to have an ID assigned.
  bool IsDynamic() const {
    return (property_flags_ & PROPERTY_DYNAMIC) != 0;
  }

  // Returns true if this package is a Runtime Resource Overlay.
  bool IsOverlay() const {
    return (property_flags_ & PROPERTY_OVERLAY) != 0;
  }

  // Returns true if this package originates from a system provided resource.
  bool IsSystem() const {
    return (property_flags_ & PROPERTY_SYSTEM) != 0;
  }

  // Returns true if this package is a custom loader and should behave like an overlay.
  bool IsCustomLoader() const {
    return (property_flags_ & PROPERTY_LOADER) != 0;
  }

  package_property_t GetPropertyFlags() const {
    return property_flags_;
  }

  // Returns the map of package name to package ID used in this LoadedPackage. At runtime, a
  // package could have been assigned a different package ID than what this LoadedPackage was
  // compiled with. AssetManager rewrites the package IDs so that they are compatible at runtime.
  const std::vector<DynamicPackageEntry>& GetDynamicPackageMap() const {
    return dynamic_package_map_;
  }

  // Populates a set of ResTable_config structs, possibly excluding configurations defined for
  // the mipmap type.
  base::expected<std::monostate, IOError> CollectConfigurations(
      bool exclude_mipmap, std::set<ResTable_config>* out_configs) const;

  // Populates a set of strings representing locales.
  // If `canonicalize` is set to true, each locale is transformed into its canonical format
  // before being inserted into the set. This may cause some equivalent locales to de-dupe.
  void CollectLocales(bool canonicalize, std::set<std::string>* out_locales) const;

  // type_idx is TT - 1 from 0xPPTTEEEE.
  inline const TypeSpec* GetTypeSpecByTypeIndex(uint8_t type_index) const {
    // If the type IDs are offset in this package, we need to take that into account when searching
    // for a type.
    const auto& type_spec = type_specs_.find(type_index + 1 - type_id_offset_);
    if (type_spec == type_specs_.end()) {
      return nullptr;
    }
    return &type_spec->second;
  }

  template <typename Func>
  void ForEachTypeSpec(Func f) const {
    for (const auto& type_spec : type_specs_) {
      f(type_spec.second, type_spec.first);
    }
  }

  // Retrieves the overlayable properties of the specified resource. If the resource is not
  // overlayable, this will return a null pointer.
  const OverlayableInfo* GetOverlayableInfo(uint32_t resid) const {
    for (const std::pair<OverlayableInfo, std::unordered_set<uint32_t>>& overlayable_info_ids
        : overlayable_infos_) {
      if (overlayable_info_ids.second.find(resid) != overlayable_info_ids.second.end()) {
        return &overlayable_info_ids.first;
      }
    }
    return nullptr;
  }

  // Retrieves whether or not the package defines overlayable resources.
  // TODO(123905379): Remove this when the enforcement of overlayable is turned on for all APK and
  // not just those that defined overlayable resources.
  bool DefinesOverlayable() const {
    return defines_overlayable_;
  }

  const std::unordered_map<std::string, std::string>& GetOverlayableMap() const {
    return overlayable_map_;
  }

  const std::map<uint32_t, uint32_t>& GetAliasResourceIdMap() const {
    return alias_id_map_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(LoadedPackage);

  LoadedPackage() = default;

  ResStringPool type_string_pool_;
  ResStringPool key_string_pool_;
  std::string package_name_;
  bool defines_overlayable_ = false;
  int package_id_ = -1;
  int type_id_offset_ = 0;
  package_property_t property_flags_ = 0U;

  std::unordered_map<uint8_t, TypeSpec> type_specs_;
  ByteBucketArray<uint32_t> resource_ids_;
  std::vector<DynamicPackageEntry> dynamic_package_map_;
  std::vector<const std::pair<OverlayableInfo, std::unordered_set<uint32_t>>> overlayable_infos_;
  std::map<uint32_t, uint32_t> alias_id_map_;

  // A map of overlayable name to actor
  std::unordered_map<std::string, std::string> overlayable_map_;
};

// Read-only view into a resource table. This class validates all data
// when loading, including offsets and lengths.
class LoadedArsc {
 public:
  // Load a resource table from memory pointed to by `data` of size `len`.
  // The lifetime of `data` must out-live the LoadedArsc returned from this method.

  static std::unique_ptr<LoadedArsc> Load(incfs::map_ptr<void> data,
                                          size_t length,
                                          const LoadedIdmap* loaded_idmap = nullptr,
                                          package_property_t property_flags = 0U);

  // Create an empty LoadedArsc. This is used when an APK has no resources.arsc.
  static std::unique_ptr<LoadedArsc> CreateEmpty();

  // Returns the string pool where all string resource values
  // (Res_value::dataType == Res_value::TYPE_STRING) are indexed.
  inline const ResStringPool* GetStringPool() const {
    return global_string_pool_.get();
  }

  // Gets a pointer to the package with the specified package ID, or nullptr if no such package
  // exists.
  const LoadedPackage* GetPackageById(uint8_t package_id) const;

  // Returns a vector of LoadedPackage pointers, representing the packages in this LoadedArsc.
  inline const std::vector<std::unique_ptr<const LoadedPackage>>& GetPackages() const {
    return packages_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(LoadedArsc);

  LoadedArsc() = default;
  bool LoadTable(
      const Chunk& chunk, const LoadedIdmap* loaded_idmap, package_property_t property_flags);

  std::unique_ptr<ResStringPool> global_string_pool_ = util::make_unique<ResStringPool>();
  std::vector<std::unique_ptr<const LoadedPackage>> packages_;
};

}  // namespace android

#endif /* LOADEDARSC_H_ */
