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

#include "androidfw/AssetManager2.h"

#include <algorithm>
#include <iterator>
#include <set>

#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "utils/ByteOrder.h"
#include "utils/Trace.h"

#ifdef _WIN32
#ifdef ERROR
#undef ERROR
#endif
#endif

#include "androidfw/ResourceUtils.h"

namespace android {

struct FindEntryResult {
  // A pointer to the resource table entry for this resource.
  // If the size of the entry is > sizeof(ResTable_entry), it can be cast to
  // a ResTable_map_entry and processed as a bag/map.
  const ResTable_entry* entry;

  // The configuration for which the resulting entry was defined. This is already swapped to host
  // endianness.
  ResTable_config config;

  // The bitmask of configuration axis with which the resource value varies.
  uint32_t type_flags;

  // The dynamic package ID map for the package from which this resource came from.
  const DynamicRefTable* dynamic_ref_table;

  // The string pool reference to the type's name. This uses a different string pool than
  // the global string pool, but this is hidden from the caller.
  StringPoolRef type_string_ref;

  // The string pool reference to the entry's name. This uses a different string pool than
  // the global string pool, but this is hidden from the caller.
  StringPoolRef entry_string_ref;
};

AssetManager2::AssetManager2() {
  memset(&configuration_, 0, sizeof(configuration_));
}

bool AssetManager2::SetApkAssets(const std::vector<const ApkAssets*>& apk_assets,
                                 bool invalidate_caches) {
  apk_assets_ = apk_assets;
  BuildDynamicRefTable();
  RebuildFilterList();
  if (invalidate_caches) {
    InvalidateCaches(static_cast<uint32_t>(-1));
  }
  return true;
}

void AssetManager2::BuildDynamicRefTable() {
  package_groups_.clear();
  package_ids_.fill(0xff);

  // 0x01 is reserved for the android package.
  int next_package_id = 0x02;
  const size_t apk_assets_count = apk_assets_.size();
  for (size_t i = 0; i < apk_assets_count; i++) {
    const LoadedArsc* loaded_arsc = apk_assets_[i]->GetLoadedArsc();

    for (const std::unique_ptr<const LoadedPackage>& package : loaded_arsc->GetPackages()) {
      // Get the package ID or assign one if a shared library.
      int package_id;
      if (package->IsDynamic()) {
        package_id = next_package_id++;
      } else {
        package_id = package->GetPackageId();
      }

      // Add the mapping for package ID to index if not present.
      uint8_t idx = package_ids_[package_id];
      if (idx == 0xff) {
        package_ids_[package_id] = idx = static_cast<uint8_t>(package_groups_.size());
        package_groups_.push_back({});
        DynamicRefTable& ref_table = package_groups_.back().dynamic_ref_table;
        ref_table.mAssignedPackageId = package_id;
        ref_table.mAppAsLib = package->IsDynamic() && package->GetPackageId() == 0x7f;
      }
      PackageGroup* package_group = &package_groups_[idx];

      // Add the package and to the set of packages with the same ID.
      package_group->packages_.push_back(ConfiguredPackage{package.get(), {}});
      package_group->cookies_.push_back(static_cast<ApkAssetsCookie>(i));

      // Add the package name -> build time ID mappings.
      for (const DynamicPackageEntry& entry : package->GetDynamicPackageMap()) {
        String16 package_name(entry.package_name.c_str(), entry.package_name.size());
        package_group->dynamic_ref_table.mEntries.replaceValueFor(
            package_name, static_cast<uint8_t>(entry.package_id));
      }
    }
  }

  // Now assign the runtime IDs so that we have a build-time to runtime ID map.
  const auto package_groups_end = package_groups_.end();
  for (auto iter = package_groups_.begin(); iter != package_groups_end; ++iter) {
    const std::string& package_name = iter->packages_[0].loaded_package_->GetPackageName();
    for (auto iter2 = package_groups_.begin(); iter2 != package_groups_end; ++iter2) {
      iter2->dynamic_ref_table.addMapping(String16(package_name.c_str(), package_name.size()),
                                          iter->dynamic_ref_table.mAssignedPackageId);
    }
  }
}

void AssetManager2::DumpToLog() const {
  base::ScopedLogSeverity _log(base::INFO);

  LOG(INFO) << base::StringPrintf("AssetManager2(this=%p)", this);

  std::string list;
  for (const auto& apk_assets : apk_assets_) {
    base::StringAppendF(&list, "%s,", apk_assets->GetPath().c_str());
  }
  LOG(INFO) << "ApkAssets: " << list;

  list = "";
  for (size_t i = 0; i < package_ids_.size(); i++) {
    if (package_ids_[i] != 0xff) {
      base::StringAppendF(&list, "%02x -> %d, ", (int)i, package_ids_[i]);
    }
  }
  LOG(INFO) << "Package ID map: " << list;

  for (const auto& package_group: package_groups_) {
    list = "";
    for (const auto& package : package_group.packages_) {
      const LoadedPackage* loaded_package = package.loaded_package_;
      base::StringAppendF(&list, "%s(%02x%s), ", loaded_package->GetPackageName().c_str(),
                          loaded_package->GetPackageId(),
                          (loaded_package->IsDynamic() ? " dynamic" : ""));
    }
    LOG(INFO) << base::StringPrintf("PG (%02x): ",
                                    package_group.dynamic_ref_table.mAssignedPackageId)
              << list;
  }
}

const ResStringPool* AssetManager2::GetStringPoolForCookie(ApkAssetsCookie cookie) const {
  if (cookie < 0 || static_cast<size_t>(cookie) >= apk_assets_.size()) {
    return nullptr;
  }
  return apk_assets_[cookie]->GetLoadedArsc()->GetStringPool();
}

const DynamicRefTable* AssetManager2::GetDynamicRefTableForPackage(uint32_t package_id) const {
  if (package_id >= package_ids_.size()) {
    return nullptr;
  }

  const size_t idx = package_ids_[package_id];
  if (idx == 0xff) {
    return nullptr;
  }
  return &package_groups_[idx].dynamic_ref_table;
}

const DynamicRefTable* AssetManager2::GetDynamicRefTableForCookie(ApkAssetsCookie cookie) const {
  for (const PackageGroup& package_group : package_groups_) {
    for (const ApkAssetsCookie& package_cookie : package_group.cookies_) {
      if (package_cookie == cookie) {
        return &package_group.dynamic_ref_table;
      }
    }
  }
  return nullptr;
}

void AssetManager2::SetConfiguration(const ResTable_config& configuration) {
  const int diff = configuration_.diff(configuration);
  configuration_ = configuration;

  if (diff) {
    RebuildFilterList();
    InvalidateCaches(static_cast<uint32_t>(diff));
  }
}

std::set<ResTable_config> AssetManager2::GetResourceConfigurations(bool exclude_system,
                                                                   bool exclude_mipmap) const {
  ATRACE_NAME("AssetManager::GetResourceConfigurations");
  std::set<ResTable_config> configurations;
  for (const PackageGroup& package_group : package_groups_) {
    for (const ConfiguredPackage& package : package_group.packages_) {
      if (exclude_system && package.loaded_package_->IsSystem()) {
        continue;
      }
      package.loaded_package_->CollectConfigurations(exclude_mipmap, &configurations);
    }
  }
  return configurations;
}

std::set<std::string> AssetManager2::GetResourceLocales(bool exclude_system,
                                                        bool merge_equivalent_languages) const {
  ATRACE_NAME("AssetManager::GetResourceLocales");
  std::set<std::string> locales;
  for (const PackageGroup& package_group : package_groups_) {
    for (const ConfiguredPackage& package : package_group.packages_) {
      if (exclude_system && package.loaded_package_->IsSystem()) {
        continue;
      }
      package.loaded_package_->CollectLocales(merge_equivalent_languages, &locales);
    }
  }
  return locales;
}

std::unique_ptr<Asset> AssetManager2::Open(const std::string& filename,
                                           Asset::AccessMode mode) const {
  const std::string new_path = "assets/" + filename;
  return OpenNonAsset(new_path, mode);
}

std::unique_ptr<Asset> AssetManager2::Open(const std::string& filename, ApkAssetsCookie cookie,
                                           Asset::AccessMode mode) const {
  const std::string new_path = "assets/" + filename;
  return OpenNonAsset(new_path, cookie, mode);
}

std::unique_ptr<AssetDir> AssetManager2::OpenDir(const std::string& dirname) const {
  ATRACE_NAME("AssetManager::OpenDir");

  std::string full_path = "assets/" + dirname;
  std::unique_ptr<SortedVector<AssetDir::FileInfo>> files =
      util::make_unique<SortedVector<AssetDir::FileInfo>>();

  // Start from the back.
  for (auto iter = apk_assets_.rbegin(); iter != apk_assets_.rend(); ++iter) {
    const ApkAssets* apk_assets = *iter;

    auto func = [&](const StringPiece& name, FileType type) {
      AssetDir::FileInfo info;
      info.setFileName(String8(name.data(), name.size()));
      info.setFileType(type);
      info.setSourceName(String8(apk_assets->GetPath().c_str()));
      files->add(info);
    };

    if (!apk_assets->ForEachFile(full_path, func)) {
      return {};
    }
  }

  std::unique_ptr<AssetDir> asset_dir = util::make_unique<AssetDir>();
  asset_dir->setFileList(files.release());
  return asset_dir;
}

// Search in reverse because that's how we used to do it and we need to preserve behaviour.
// This is unfortunate, because ClassLoaders delegate to the parent first, so the order
// is inconsistent for split APKs.
std::unique_ptr<Asset> AssetManager2::OpenNonAsset(const std::string& filename,
                                                   Asset::AccessMode mode,
                                                   ApkAssetsCookie* out_cookie) const {
  for (int32_t i = apk_assets_.size() - 1; i >= 0; i--) {
    std::unique_ptr<Asset> asset = apk_assets_[i]->Open(filename, mode);
    if (asset) {
      if (out_cookie != nullptr) {
        *out_cookie = i;
      }
      return asset;
    }
  }

  if (out_cookie != nullptr) {
    *out_cookie = kInvalidCookie;
  }
  return {};
}

std::unique_ptr<Asset> AssetManager2::OpenNonAsset(const std::string& filename,
                                                   ApkAssetsCookie cookie,
                                                   Asset::AccessMode mode) const {
  if (cookie < 0 || static_cast<size_t>(cookie) >= apk_assets_.size()) {
    return {};
  }
  return apk_assets_[cookie]->Open(filename, mode);
}

ApkAssetsCookie AssetManager2::FindEntry(uint32_t resid, uint16_t density_override,
                                         bool /*stop_at_first_match*/,
                                         FindEntryResult* out_entry) const {
  // Might use this if density_override != 0.
  ResTable_config density_override_config;

  // Select our configuration or generate a density override configuration.
  const ResTable_config* desired_config = &configuration_;
  if (density_override != 0 && density_override != configuration_.density) {
    density_override_config = configuration_;
    density_override_config.density = density_override;
    desired_config = &density_override_config;
  }

  if (!is_valid_resid(resid)) {
    LOG(ERROR) << base::StringPrintf("Invalid ID 0x%08x.", resid);
    return kInvalidCookie;
  }

  const uint32_t package_id = get_package_id(resid);
  const uint8_t type_idx = get_type_id(resid) - 1;
  const uint16_t entry_idx = get_entry_id(resid);

  const uint8_t package_idx = package_ids_[package_id];
  if (package_idx == 0xff) {
    LOG(ERROR) << base::StringPrintf("No package ID %02x found for ID 0x%08x.", package_id, resid);
    return kInvalidCookie;
  }

  const PackageGroup& package_group = package_groups_[package_idx];
  const size_t package_count = package_group.packages_.size();

  ApkAssetsCookie best_cookie = kInvalidCookie;
  const LoadedPackage* best_package = nullptr;
  const ResTable_type* best_type = nullptr;
  const ResTable_config* best_config = nullptr;
  ResTable_config best_config_copy;
  uint32_t best_offset = 0u;
  uint32_t type_flags = 0u;

  // If desired_config is the same as the set configuration, then we can use our filtered list
  // and we don't need to match the configurations, since they already matched.
  const bool use_fast_path = desired_config == &configuration_;

  for (size_t pi = 0; pi < package_count; pi++) {
    const ConfiguredPackage& loaded_package_impl = package_group.packages_[pi];
    const LoadedPackage* loaded_package = loaded_package_impl.loaded_package_;
    ApkAssetsCookie cookie = package_group.cookies_[pi];

    // If the type IDs are offset in this package, we need to take that into account when searching
    // for a type.
    const TypeSpec* type_spec = loaded_package->GetTypeSpecByTypeIndex(type_idx);
    if (UNLIKELY(type_spec == nullptr)) {
      continue;
    }

    uint16_t local_entry_idx = entry_idx;

    // If there is an IDMAP supplied with this package, translate the entry ID.
    if (type_spec->idmap_entries != nullptr) {
      if (!LoadedIdmap::Lookup(type_spec->idmap_entries, local_entry_idx, &local_entry_idx)) {
        // There is no mapping, so the resource is not meant to be in this overlay package.
        continue;
      }
    }

    type_flags |= type_spec->GetFlagsForEntryIndex(local_entry_idx);

    // If the package is an overlay, then even configurations that are the same MUST be chosen.
    const bool package_is_overlay = loaded_package->IsOverlay();

    const FilteredConfigGroup& filtered_group = loaded_package_impl.filtered_configs_[type_idx];
    if (use_fast_path) {
      const std::vector<ResTable_config>& candidate_configs = filtered_group.configurations;
      const size_t type_count = candidate_configs.size();
      for (uint32_t i = 0; i < type_count; i++) {
        const ResTable_config& this_config = candidate_configs[i];

        // We can skip calling ResTable_config::match() because we know that all candidate
        // configurations that do NOT match have been filtered-out.
        if ((best_config == nullptr || this_config.isBetterThan(*best_config, desired_config)) ||
            (package_is_overlay && this_config.compare(*best_config) == 0)) {
          // The configuration matches and is better than the previous selection.
          // Find the entry value if it exists for this configuration.
          const ResTable_type* type_chunk = filtered_group.types[i];
          const uint32_t offset = LoadedPackage::GetEntryOffset(type_chunk, local_entry_idx);
          if (offset == ResTable_type::NO_ENTRY) {
            continue;
          }

          best_cookie = cookie;
          best_package = loaded_package;
          best_type = type_chunk;
          best_config = &this_config;
          best_offset = offset;
        }
      }
    } else {
      // This is the slower path, which doesn't use the filtered list of configurations.
      // Here we must read the ResTable_config from the mmapped APK, convert it to host endianness
      // and fill in any new fields that did not exist when the APK was compiled.
      // Furthermore when selecting configurations we can't just record the pointer to the
      // ResTable_config, we must copy it.
      const auto iter_end = type_spec->types + type_spec->type_count;
      for (auto iter = type_spec->types; iter != iter_end; ++iter) {
        ResTable_config this_config;
        this_config.copyFromDtoH((*iter)->config);

        if (this_config.match(*desired_config)) {
          if ((best_config == nullptr || this_config.isBetterThan(*best_config, desired_config)) ||
              (package_is_overlay && this_config.compare(*best_config) == 0)) {
            // The configuration matches and is better than the previous selection.
            // Find the entry value if it exists for this configuration.
            const uint32_t offset = LoadedPackage::GetEntryOffset(*iter, local_entry_idx);
            if (offset == ResTable_type::NO_ENTRY) {
              continue;
            }

            best_cookie = cookie;
            best_package = loaded_package;
            best_type = *iter;
            best_config_copy = this_config;
            best_config = &best_config_copy;
            best_offset = offset;
          }
        }
      }
    }
  }

  if (UNLIKELY(best_cookie == kInvalidCookie)) {
    return kInvalidCookie;
  }

  const ResTable_entry* best_entry = LoadedPackage::GetEntryFromOffset(best_type, best_offset);
  if (UNLIKELY(best_entry == nullptr)) {
    return kInvalidCookie;
  }

  out_entry->entry = best_entry;
  out_entry->config = *best_config;
  out_entry->type_flags = type_flags;
  out_entry->type_string_ref = StringPoolRef(best_package->GetTypeStringPool(), best_type->id - 1);
  out_entry->entry_string_ref =
      StringPoolRef(best_package->GetKeyStringPool(), best_entry->key.index);
  out_entry->dynamic_ref_table = &package_group.dynamic_ref_table;
  return best_cookie;
}

bool AssetManager2::GetResourceName(uint32_t resid, ResourceName* out_name) const {
  FindEntryResult entry;
  ApkAssetsCookie cookie =
      FindEntry(resid, 0u /* density_override */, true /* stop_at_first_match */, &entry);
  if (cookie == kInvalidCookie) {
    return false;
  }

  const LoadedPackage* package =
      apk_assets_[cookie]->GetLoadedArsc()->GetPackageById(get_package_id(resid));
  if (package == nullptr) {
    return false;
  }

  out_name->package = package->GetPackageName().data();
  out_name->package_len = package->GetPackageName().size();

  out_name->type = entry.type_string_ref.string8(&out_name->type_len);
  out_name->type16 = nullptr;
  if (out_name->type == nullptr) {
    out_name->type16 = entry.type_string_ref.string16(&out_name->type_len);
    if (out_name->type16 == nullptr) {
      return false;
    }
  }

  out_name->entry = entry.entry_string_ref.string8(&out_name->entry_len);
  out_name->entry16 = nullptr;
  if (out_name->entry == nullptr) {
    out_name->entry16 = entry.entry_string_ref.string16(&out_name->entry_len);
    if (out_name->entry16 == nullptr) {
      return false;
    }
  }
  return true;
}

bool AssetManager2::GetResourceFlags(uint32_t resid, uint32_t* out_flags) const {
  FindEntryResult entry;
  ApkAssetsCookie cookie =
      FindEntry(resid, 0u /* density_override */, false /* stop_at_first_match */, &entry);
  if (cookie != kInvalidCookie) {
    *out_flags = entry.type_flags;
    return cookie;
  }
  return kInvalidCookie;
}

ApkAssetsCookie AssetManager2::GetResource(uint32_t resid, bool may_be_bag,
                                           uint16_t density_override, Res_value* out_value,
                                           ResTable_config* out_selected_config,
                                           uint32_t* out_flags) const {
  FindEntryResult entry;
  ApkAssetsCookie cookie =
      FindEntry(resid, density_override, false /* stop_at_first_match */, &entry);
  if (cookie == kInvalidCookie) {
    return kInvalidCookie;
  }

  if (dtohs(entry.entry->flags) & ResTable_entry::FLAG_COMPLEX) {
    if (!may_be_bag) {
      LOG(ERROR) << base::StringPrintf("Resource %08x is a complex map type.", resid);
      return kInvalidCookie;
    }

    // Create a reference since we can't represent this complex type as a Res_value.
    out_value->dataType = Res_value::TYPE_REFERENCE;
    out_value->data = resid;
    *out_selected_config = entry.config;
    *out_flags = entry.type_flags;
    return cookie;
  }

  const Res_value* device_value = reinterpret_cast<const Res_value*>(
      reinterpret_cast<const uint8_t*>(entry.entry) + dtohs(entry.entry->size));
  out_value->copyFrom_dtoh(*device_value);

  // Convert the package ID to the runtime assigned package ID.
  entry.dynamic_ref_table->lookupResourceValue(out_value);

  *out_selected_config = entry.config;
  *out_flags = entry.type_flags;
  return cookie;
}

ApkAssetsCookie AssetManager2::ResolveReference(ApkAssetsCookie cookie, Res_value* in_out_value,
                                                ResTable_config* in_out_selected_config,
                                                uint32_t* in_out_flags,
                                                uint32_t* out_last_reference) const {
  constexpr const int kMaxIterations = 20;

  for (size_t iteration = 0u; in_out_value->dataType == Res_value::TYPE_REFERENCE &&
                              in_out_value->data != 0u && iteration < kMaxIterations;
       iteration++) {
    *out_last_reference = in_out_value->data;
    uint32_t new_flags = 0u;
    cookie = GetResource(in_out_value->data, true /*may_be_bag*/, 0u /*density_override*/,
                         in_out_value, in_out_selected_config, &new_flags);
    if (cookie == kInvalidCookie) {
      return kInvalidCookie;
    }
    if (in_out_flags != nullptr) {
      *in_out_flags |= new_flags;
    }
    if (*out_last_reference == in_out_value->data) {
      // This reference can't be resolved, so exit now and let the caller deal with it.
      return cookie;
    }
  }
  return cookie;
}

const ResolvedBag* AssetManager2::GetBag(uint32_t resid) {
  auto found_resids = std::vector<uint32_t>();
  return GetBag(resid, found_resids);
}

const ResolvedBag* AssetManager2::GetBag(uint32_t resid, std::vector<uint32_t>& child_resids) {
  ATRACE_NAME("AssetManager::GetBag");

  auto cached_iter = cached_bags_.find(resid);
  if (cached_iter != cached_bags_.end()) {
    return cached_iter->second.get();
  }

  FindEntryResult entry;
  ApkAssetsCookie cookie =
      FindEntry(resid, 0u /* density_override */, false /* stop_at_first_match */, &entry);
  if (cookie == kInvalidCookie) {
    return nullptr;
  }

  // Check that the size of the entry header is at least as big as
  // the desired ResTable_map_entry. Also verify that the entry
  // was intended to be a map.
  if (dtohs(entry.entry->size) < sizeof(ResTable_map_entry) ||
      (dtohs(entry.entry->flags) & ResTable_entry::FLAG_COMPLEX) == 0) {
    // Not a bag, nothing to do.
    return nullptr;
  }

  const ResTable_map_entry* map = reinterpret_cast<const ResTable_map_entry*>(entry.entry);
  const ResTable_map* map_entry =
      reinterpret_cast<const ResTable_map*>(reinterpret_cast<const uint8_t*>(map) + map->size);
  const ResTable_map* const map_entry_end = map_entry + dtohl(map->count);

  // Keep track of ids that have already been seen to prevent infinite loops caused by circular
  // dependencies between bags
  child_resids.push_back(resid);

  uint32_t parent_resid = dtohl(map->parent.ident);
  if (parent_resid == 0 || std::find(child_resids.begin(), child_resids.end(), parent_resid)
      != child_resids.end()) {
    // There is no parent or that a circular dependency exist, meaning there is nothing to
    // inherit and we can do a simple copy of the entries in the map.
    const size_t entry_count = map_entry_end - map_entry;
    util::unique_cptr<ResolvedBag> new_bag{reinterpret_cast<ResolvedBag*>(
        malloc(sizeof(ResolvedBag) + (entry_count * sizeof(ResolvedBag::Entry))))};
    ResolvedBag::Entry* new_entry = new_bag->entries;
    for (; map_entry != map_entry_end; ++map_entry) {
      uint32_t new_key = dtohl(map_entry->name.ident);
      if (!is_internal_resid(new_key)) {
        // Attributes, arrays, etc don't have a resource id as the name. They specify
        // other data, which would be wrong to change via a lookup.
        if (entry.dynamic_ref_table->lookupResourceId(&new_key) != NO_ERROR) {
          LOG(ERROR) << base::StringPrintf("Failed to resolve key 0x%08x in bag 0x%08x.", new_key,
                                           resid);
          return nullptr;
        }
      }
      new_entry->cookie = cookie;
      new_entry->key = new_key;
      new_entry->key_pool = nullptr;
      new_entry->type_pool = nullptr;
      new_entry->value.copyFrom_dtoh(map_entry->value);
      status_t err = entry.dynamic_ref_table->lookupResourceValue(&new_entry->value);
      if (err != NO_ERROR) {
        LOG(ERROR) << base::StringPrintf(
            "Failed to resolve value t=0x%02x d=0x%08x for key 0x%08x.", new_entry->value.dataType,
            new_entry->value.data, new_key);
        return nullptr;
      }
      ++new_entry;
    }
    new_bag->type_spec_flags = entry.type_flags;
    new_bag->entry_count = static_cast<uint32_t>(entry_count);
    ResolvedBag* result = new_bag.get();
    cached_bags_[resid] = std::move(new_bag);
    return result;
  }

  // In case the parent is a dynamic reference, resolve it.
  entry.dynamic_ref_table->lookupResourceId(&parent_resid);

  // Get the parent and do a merge of the keys.
  const ResolvedBag* parent_bag = GetBag(parent_resid, child_resids);
  if (parent_bag == nullptr) {
    // Failed to get the parent that should exist.
    LOG(ERROR) << base::StringPrintf("Failed to find parent 0x%08x of bag 0x%08x.", parent_resid,
                                     resid);
    return nullptr;
  }

  // Create the max possible entries we can make. Once we construct the bag,
  // we will realloc to fit to size.
  const size_t max_count = parent_bag->entry_count + dtohl(map->count);
  util::unique_cptr<ResolvedBag> new_bag{reinterpret_cast<ResolvedBag*>(
      malloc(sizeof(ResolvedBag) + (max_count * sizeof(ResolvedBag::Entry))))};
  ResolvedBag::Entry* new_entry = new_bag->entries;

  const ResolvedBag::Entry* parent_entry = parent_bag->entries;
  const ResolvedBag::Entry* const parent_entry_end = parent_entry + parent_bag->entry_count;

  // The keys are expected to be in sorted order. Merge the two bags.
  while (map_entry != map_entry_end && parent_entry != parent_entry_end) {
    uint32_t child_key = dtohl(map_entry->name.ident);
    if (!is_internal_resid(child_key)) {
      if (entry.dynamic_ref_table->lookupResourceId(&child_key) != NO_ERROR) {
        LOG(ERROR) << base::StringPrintf("Failed to resolve key 0x%08x in bag 0x%08x.", child_key,
                                         resid);
        return nullptr;
      }
    }

    if (child_key <= parent_entry->key) {
      // Use the child key if it comes before the parent
      // or is equal to the parent (overrides).
      new_entry->cookie = cookie;
      new_entry->key = child_key;
      new_entry->key_pool = nullptr;
      new_entry->type_pool = nullptr;
      new_entry->value.copyFrom_dtoh(map_entry->value);
      status_t err = entry.dynamic_ref_table->lookupResourceValue(&new_entry->value);
      if (err != NO_ERROR) {
        LOG(ERROR) << base::StringPrintf(
            "Failed to resolve value t=0x%02x d=0x%08x for key 0x%08x.", new_entry->value.dataType,
            new_entry->value.data, child_key);
        return nullptr;
      }
      ++map_entry;
    } else {
      // Take the parent entry as-is.
      memcpy(new_entry, parent_entry, sizeof(*new_entry));
    }

    if (child_key >= parent_entry->key) {
      // Move to the next parent entry if we used it or it was overridden.
      ++parent_entry;
    }
    // Increment to the next entry to fill.
    ++new_entry;
  }

  // Finish the child entries if they exist.
  while (map_entry != map_entry_end) {
    uint32_t new_key = dtohl(map_entry->name.ident);
    if (!is_internal_resid(new_key)) {
      if (entry.dynamic_ref_table->lookupResourceId(&new_key) != NO_ERROR) {
        LOG(ERROR) << base::StringPrintf("Failed to resolve key 0x%08x in bag 0x%08x.", new_key,
                                         resid);
        return nullptr;
      }
    }
    new_entry->cookie = cookie;
    new_entry->key = new_key;
    new_entry->key_pool = nullptr;
    new_entry->type_pool = nullptr;
    new_entry->value.copyFrom_dtoh(map_entry->value);
    status_t err = entry.dynamic_ref_table->lookupResourceValue(&new_entry->value);
    if (err != NO_ERROR) {
      LOG(ERROR) << base::StringPrintf("Failed to resolve value t=0x%02x d=0x%08x for key 0x%08x.",
                                       new_entry->value.dataType, new_entry->value.data, new_key);
      return nullptr;
    }
    ++map_entry;
    ++new_entry;
  }

  // Finish the parent entries if they exist.
  if (parent_entry != parent_entry_end) {
    // Take the rest of the parent entries as-is.
    const size_t num_entries_to_copy = parent_entry_end - parent_entry;
    memcpy(new_entry, parent_entry, num_entries_to_copy * sizeof(*new_entry));
    new_entry += num_entries_to_copy;
  }

  // Resize the resulting array to fit.
  const size_t actual_count = new_entry - new_bag->entries;
  if (actual_count != max_count) {
    new_bag.reset(reinterpret_cast<ResolvedBag*>(realloc(
        new_bag.release(), sizeof(ResolvedBag) + (actual_count * sizeof(ResolvedBag::Entry)))));
  }

  // Combine flags from the parent and our own bag.
  new_bag->type_spec_flags = entry.type_flags | parent_bag->type_spec_flags;
  new_bag->entry_count = static_cast<uint32_t>(actual_count);
  ResolvedBag* result = new_bag.get();
  cached_bags_[resid] = std::move(new_bag);
  return result;
}

static bool Utf8ToUtf16(const StringPiece& str, std::u16string* out) {
  ssize_t len =
      utf8_to_utf16_length(reinterpret_cast<const uint8_t*>(str.data()), str.size(), false);
  if (len < 0) {
    return false;
  }
  out->resize(static_cast<size_t>(len));
  utf8_to_utf16(reinterpret_cast<const uint8_t*>(str.data()), str.size(), &*out->begin(),
                static_cast<size_t>(len + 1));
  return true;
}

uint32_t AssetManager2::GetResourceId(const std::string& resource_name,
                                      const std::string& fallback_type,
                                      const std::string& fallback_package) const {
  StringPiece package_name, type, entry;
  if (!ExtractResourceName(resource_name, &package_name, &type, &entry)) {
    return 0u;
  }

  if (entry.empty()) {
    return 0u;
  }

  if (package_name.empty()) {
    package_name = fallback_package;
  }

  if (type.empty()) {
    type = fallback_type;
  }

  std::u16string type16;
  if (!Utf8ToUtf16(type, &type16)) {
    return 0u;
  }

  std::u16string entry16;
  if (!Utf8ToUtf16(entry, &entry16)) {
    return 0u;
  }

  const StringPiece16 kAttr16 = u"attr";
  const static std::u16string kAttrPrivate16 = u"^attr-private";

  for (const PackageGroup& package_group : package_groups_) {
    for (const ConfiguredPackage& package_impl : package_group.packages_) {
      const LoadedPackage* package = package_impl.loaded_package_;
      if (package_name != package->GetPackageName()) {
        // All packages in the same group are expected to have the same package name.
        break;
      }

      uint32_t resid = package->FindEntryByName(type16, entry16);
      if (resid == 0u && kAttr16 == type16) {
        // Private attributes in libraries (such as the framework) are sometimes encoded
        // under the type '^attr-private' in order to leave the ID space of public 'attr'
        // free for future additions. Check '^attr-private' for the same name.
        resid = package->FindEntryByName(kAttrPrivate16, entry16);
      }

      if (resid != 0u) {
        return fix_package_id(resid, package_group.dynamic_ref_table.mAssignedPackageId);
      }
    }
  }
  return 0u;
}

void AssetManager2::RebuildFilterList() {
  for (PackageGroup& group : package_groups_) {
    for (ConfiguredPackage& impl : group.packages_) {
      // Destroy it.
      impl.filtered_configs_.~ByteBucketArray();

      // Re-create it.
      new (&impl.filtered_configs_) ByteBucketArray<FilteredConfigGroup>();

      // Create the filters here.
      impl.loaded_package_->ForEachTypeSpec([&](const TypeSpec* spec, uint8_t type_index) {
        FilteredConfigGroup& group = impl.filtered_configs_.editItemAt(type_index);
        const auto iter_end = spec->types + spec->type_count;
        for (auto iter = spec->types; iter != iter_end; ++iter) {
          ResTable_config this_config;
          this_config.copyFromDtoH((*iter)->config);
          if (this_config.match(configuration_)) {
            group.configurations.push_back(this_config);
            group.types.push_back(*iter);
          }
        }
      });
    }
  }
}

void AssetManager2::InvalidateCaches(uint32_t diff) {
  if (diff == 0xffffffffu) {
    // Everything must go.
    cached_bags_.clear();
    return;
  }

  // Be more conservative with what gets purged. Only if the bag has other possible
  // variations with respect to what changed (diff) should we remove it.
  for (auto iter = cached_bags_.cbegin(); iter != cached_bags_.cend();) {
    if (diff & iter->second->type_spec_flags) {
      iter = cached_bags_.erase(iter);
    } else {
      ++iter;
    }
  }
}

std::unique_ptr<Theme> AssetManager2::NewTheme() {
  return std::unique_ptr<Theme>(new Theme(this));
}

Theme::Theme(AssetManager2* asset_manager) : asset_manager_(asset_manager) {
}

Theme::~Theme() = default;

namespace {

struct ThemeEntry {
  ApkAssetsCookie cookie;
  uint32_t type_spec_flags;
  Res_value value;
};

struct ThemeType {
  int entry_count;
  ThemeEntry entries[0];
};

constexpr size_t kTypeCount = std::numeric_limits<uint8_t>::max() + 1;

}  // namespace

struct Theme::Package {
  // Each element of Type will be a dynamically sized object
  // allocated to have the entries stored contiguously with the Type.
  std::array<util::unique_cptr<ThemeType>, kTypeCount> types;
};

bool Theme::ApplyStyle(uint32_t resid, bool force) {
  ATRACE_NAME("Theme::ApplyStyle");

  const ResolvedBag* bag = asset_manager_->GetBag(resid);
  if (bag == nullptr) {
    return false;
  }

  // Merge the flags from this style.
  type_spec_flags_ |= bag->type_spec_flags;

  int last_type_idx = -1;
  int last_package_idx = -1;
  Package* last_package = nullptr;
  ThemeType* last_type = nullptr;

  // Iterate backwards, because each bag is sorted in ascending key ID order, meaning we will only
  // need to perform one resize per type.
  using reverse_bag_iterator = std::reverse_iterator<const ResolvedBag::Entry*>;
  const auto bag_iter_end = reverse_bag_iterator(begin(bag));
  for (auto bag_iter = reverse_bag_iterator(end(bag)); bag_iter != bag_iter_end; ++bag_iter) {
    const uint32_t attr_resid = bag_iter->key;

    // If the resource ID passed in is not a style, the key can be some other identifier that is not
    // a resource ID. We should fail fast instead of operating with strange resource IDs.
    if (!is_valid_resid(attr_resid)) {
      return false;
    }

    // We don't use the 0-based index for the type so that we can avoid doing ID validation
    // upon lookup. Instead, we keep space for the type ID 0 in our data structures. Since
    // the construction of this type is guarded with a resource ID check, it will never be
    // populated, and querying type ID 0 will always fail.
    const int package_idx = get_package_id(attr_resid);
    const int type_idx = get_type_id(attr_resid);
    const int entry_idx = get_entry_id(attr_resid);

    if (last_package_idx != package_idx) {
      std::unique_ptr<Package>& package = packages_[package_idx];
      if (package == nullptr) {
        package.reset(new Package());
      }
      last_package_idx = package_idx;
      last_package = package.get();
      last_type_idx = -1;
    }

    if (last_type_idx != type_idx) {
      util::unique_cptr<ThemeType>& type = last_package->types[type_idx];
      if (type == nullptr) {
        // Allocate enough memory to contain this entry_idx. Since we're iterating in reverse over
        // a sorted list of attributes, this shouldn't be resized again during this method call.
        type.reset(reinterpret_cast<ThemeType*>(
            calloc(sizeof(ThemeType) + (entry_idx + 1) * sizeof(ThemeEntry), 1)));
        type->entry_count = entry_idx + 1;
      } else if (entry_idx >= type->entry_count) {
        // Reallocate the memory to contain this entry_idx. Since we're iterating in reverse over
        // a sorted list of attributes, this shouldn't be resized again during this method call.
        const int new_count = entry_idx + 1;
        type.reset(reinterpret_cast<ThemeType*>(
            realloc(type.release(), sizeof(ThemeType) + (new_count * sizeof(ThemeEntry)))));

        // Clear out the newly allocated space (which isn't zeroed).
        memset(type->entries + type->entry_count, 0,
               (new_count - type->entry_count) * sizeof(ThemeEntry));
        type->entry_count = new_count;
      }
      last_type_idx = type_idx;
      last_type = type.get();
    }

    ThemeEntry& entry = last_type->entries[entry_idx];
    if (force || (entry.value.dataType == Res_value::TYPE_NULL &&
                  entry.value.data != Res_value::DATA_NULL_EMPTY)) {
      entry.cookie = bag_iter->cookie;
      entry.type_spec_flags |= bag->type_spec_flags;
      entry.value = bag_iter->value;
    }
  }
  return true;
}

ApkAssetsCookie Theme::GetAttribute(uint32_t resid, Res_value* out_value,
                                    uint32_t* out_flags) const {
  int cnt = 20;

  uint32_t type_spec_flags = 0u;

  do {
    const int package_idx = get_package_id(resid);
    const Package* package = packages_[package_idx].get();
    if (package != nullptr) {
      // The themes are constructed with a 1-based type ID, so no need to decrement here.
      const int type_idx = get_type_id(resid);
      const ThemeType* type = package->types[type_idx].get();
      if (type != nullptr) {
        const int entry_idx = get_entry_id(resid);
        if (entry_idx < type->entry_count) {
          const ThemeEntry& entry = type->entries[entry_idx];
          type_spec_flags |= entry.type_spec_flags;

          if (entry.value.dataType == Res_value::TYPE_ATTRIBUTE) {
            if (cnt > 0) {
              cnt--;
              resid = entry.value.data;
              continue;
            }
            return kInvalidCookie;
          }

          // @null is different than @empty.
          if (entry.value.dataType == Res_value::TYPE_NULL &&
              entry.value.data != Res_value::DATA_NULL_EMPTY) {
            return kInvalidCookie;
          }

          *out_value = entry.value;
          *out_flags = type_spec_flags;
          return entry.cookie;
        }
      }
    }
    break;
  } while (true);
  return kInvalidCookie;
}

ApkAssetsCookie Theme::ResolveAttributeReference(ApkAssetsCookie cookie, Res_value* in_out_value,
                                                 ResTable_config* in_out_selected_config,
                                                 uint32_t* in_out_type_spec_flags,
                                                 uint32_t* out_last_ref) const {
  if (in_out_value->dataType == Res_value::TYPE_ATTRIBUTE) {
    uint32_t new_flags;
    cookie = GetAttribute(in_out_value->data, in_out_value, &new_flags);
    if (cookie == kInvalidCookie) {
      return kInvalidCookie;
    }

    if (in_out_type_spec_flags != nullptr) {
      *in_out_type_spec_flags |= new_flags;
    }
  }
  return asset_manager_->ResolveReference(cookie, in_out_value, in_out_selected_config,
                                          in_out_type_spec_flags, out_last_ref);
}

void Theme::Clear() {
  type_spec_flags_ = 0u;
  for (std::unique_ptr<Package>& package : packages_) {
    package.reset();
  }
}

bool Theme::SetTo(const Theme& o) {
  if (this == &o) {
    return true;
  }

  type_spec_flags_ = o.type_spec_flags_;

  const bool copy_only_system = asset_manager_ != o.asset_manager_;

  for (size_t p = 0; p < packages_.size(); p++) {
    const Package* package = o.packages_[p].get();
    if (package == nullptr || (copy_only_system && p != 0x01)) {
      // The other theme doesn't have this package, clear ours.
      packages_[p].reset();
      continue;
    }

    if (packages_[p] == nullptr) {
      // The other theme has this package, but we don't. Make one.
      packages_[p].reset(new Package());
    }

    for (size_t t = 0; t < package->types.size(); t++) {
      const ThemeType* type = package->types[t].get();
      if (type == nullptr) {
        // The other theme doesn't have this type, clear ours.
        packages_[p]->types[t].reset();
        continue;
      }

      // Create a new type and update it to theirs.
      const size_t type_alloc_size = sizeof(ThemeType) + (type->entry_count * sizeof(ThemeEntry));
      void* copied_data = malloc(type_alloc_size);
      memcpy(copied_data, type, type_alloc_size);
      packages_[p]->types[t].reset(reinterpret_cast<ThemeType*>(copied_data));
    }
  }
  return true;
}

}  // namespace android
