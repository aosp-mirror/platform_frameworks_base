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

#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "utils/ByteOrder.h"
#include "utils/Trace.h"

#ifdef _WIN32
#ifdef ERROR
#undef ERROR
#endif
#endif

namespace android {

AssetManager2::AssetManager2() { memset(&configuration_, 0, sizeof(configuration_)); }

bool AssetManager2::SetApkAssets(const std::vector<const ApkAssets*>& apk_assets,
                                 bool invalidate_caches) {
  apk_assets_ = apk_assets;
  BuildDynamicRefTable();
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
    const ApkAssets* apk_asset = apk_assets_[i];
    for (const std::unique_ptr<const LoadedPackage>& package :
         apk_asset->GetLoadedArsc()->GetPackages()) {
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
        package_groups_.back().dynamic_ref_table.mAssignedPackageId = package_id;
      }
      PackageGroup* package_group = &package_groups_[idx];

      // Add the package and to the set of packages with the same ID.
      package_group->packages_.push_back(package.get());
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
    const std::string& package_name = iter->packages_[0]->GetPackageName();
    for (auto iter2 = package_groups_.begin(); iter2 != package_groups_end; ++iter2) {
      iter2->dynamic_ref_table.addMapping(String16(package_name.c_str(), package_name.size()),
                                          iter->dynamic_ref_table.mAssignedPackageId);
    }
  }
}

void AssetManager2::DumpToLog() const {
  base::ScopedLogSeverity _log(base::INFO);

  std::string list;
  for (size_t i = 0; i < package_ids_.size(); i++) {
    if (package_ids_[i] != 0xff) {
      base::StringAppendF(&list, "%02x -> %d, ", (int) i, package_ids_[i]);
    }
  }
  LOG(INFO) << "Package ID map: " << list;

  for (const auto& package_group: package_groups_) {
      list = "";
      for (const auto& package : package_group.packages_) {
        base::StringAppendF(&list, "%s(%02x), ", package->GetPackageName().c_str(), package->GetPackageId());
      }
      LOG(INFO) << base::StringPrintf("PG (%02x): ", package_group.dynamic_ref_table.mAssignedPackageId) << list;
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

void AssetManager2::SetConfiguration(const ResTable_config& configuration) {
  const int diff = configuration_.diff(configuration);
  configuration_ = configuration;

  if (diff) {
    InvalidateCaches(static_cast<uint32_t>(diff));
  }
}

std::unique_ptr<Asset> AssetManager2::Open(const std::string& filename, Asset::AccessMode mode) {
  const std::string new_path = "assets/" + filename;
  return OpenNonAsset(new_path, mode);
}

std::unique_ptr<Asset> AssetManager2::Open(const std::string& filename, ApkAssetsCookie cookie,
                                           Asset::AccessMode mode) {
  const std::string new_path = "assets/" + filename;
  return OpenNonAsset(new_path, cookie, mode);
}

// Search in reverse because that's how we used to do it and we need to preserve behaviour.
// This is unfortunate, because ClassLoaders delegate to the parent first, so the order
// is inconsistent for split APKs.
std::unique_ptr<Asset> AssetManager2::OpenNonAsset(const std::string& filename,
                                                   Asset::AccessMode mode,
                                                   ApkAssetsCookie* out_cookie) {
  ATRACE_CALL();
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
                                                   ApkAssetsCookie cookie, Asset::AccessMode mode) {
  ATRACE_CALL();
  if (cookie < 0 || static_cast<size_t>(cookie) >= apk_assets_.size()) {
    return {};
  }
  return apk_assets_[cookie]->Open(filename, mode);
}

ApkAssetsCookie AssetManager2::FindEntry(uint32_t resid, uint16_t density_override,
                                         bool stop_at_first_match, LoadedArscEntry* out_entry,
                                         ResTable_config* out_selected_config,
                                         uint32_t* out_flags) {
  ATRACE_CALL();

  // Might use this if density_override != 0.
  ResTable_config density_override_config;

  // Select our configuration or generate a density override configuration.
  ResTable_config* desired_config = &configuration_;
  if (density_override != 0 && density_override != configuration_.density) {
    density_override_config = configuration_;
    density_override_config.density = density_override;
    desired_config = &density_override_config;
  }

  const uint32_t package_id = util::get_package_id(resid);
  const uint8_t type_id = util::get_type_id(resid);
  const uint16_t entry_id = util::get_entry_id(resid);

  if (type_id == 0) {
    LOG(ERROR) << base::StringPrintf("Invalid ID 0x%08x.", resid);
    return kInvalidCookie;
  }

  const uint8_t idx = package_ids_[package_id];
  if (idx == 0xff) {
    LOG(ERROR) << base::StringPrintf("No package ID %02x found for ID 0x%08x.", package_id, resid);
    return kInvalidCookie;
  }

  LoadedArscEntry best_entry;
  ResTable_config best_config;
  ApkAssetsCookie best_cookie = kInvalidCookie;
  uint32_t cumulated_flags = 0u;

  const PackageGroup& package_group = package_groups_[idx];
  const size_t package_count = package_group.packages_.size();
  for (size_t i = 0; i < package_count; i++) {
    LoadedArscEntry current_entry;
    ResTable_config current_config;
    uint32_t current_flags = 0;

    const LoadedPackage* loaded_package = package_group.packages_[i];
    if (!loaded_package->FindEntry(type_id - 1, entry_id, *desired_config, &current_entry,
                                   &current_config, &current_flags)) {
      continue;
    }

    cumulated_flags |= current_flags;

    if (best_cookie == kInvalidCookie || current_config.isBetterThan(best_config, desired_config)) {
      best_entry = current_entry;
      best_config = current_config;
      best_cookie = package_group.cookies_[i];
      if (stop_at_first_match) {
        break;
      }
    }
  }

  if (best_cookie == kInvalidCookie) {
    return kInvalidCookie;
  }

  *out_entry = best_entry;
  out_entry->dynamic_ref_table = &package_group.dynamic_ref_table;
  *out_selected_config = best_config;
  *out_flags = cumulated_flags;
  return best_cookie;
}

bool AssetManager2::GetResourceName(uint32_t resid, ResourceName* out_name) {
  ATRACE_CALL();

  LoadedArscEntry entry;
  ResTable_config config;
  uint32_t flags = 0u;
  ApkAssetsCookie cookie = FindEntry(resid, 0u /* density_override */,
                                     true /* stop_at_first_match */, &entry, &config, &flags);
  if (cookie == kInvalidCookie) {
    return false;
  }

  const LoadedPackage* package = apk_assets_[cookie]->GetLoadedArsc()->GetPackageForId(resid);
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

bool AssetManager2::GetResourceFlags(uint32_t resid, uint32_t* out_flags) {
  LoadedArscEntry entry;
  ResTable_config config;
  ApkAssetsCookie cookie = FindEntry(resid, 0u /* density_override */,
                                     false /* stop_at_first_match */, &entry, &config, out_flags);
  return cookie != kInvalidCookie;
}

ApkAssetsCookie AssetManager2::GetResource(uint32_t resid, bool may_be_bag,
                                           uint16_t density_override, Res_value* out_value,
                                           ResTable_config* out_selected_config,
                                           uint32_t* out_flags) {
  ATRACE_CALL();

  LoadedArscEntry entry;
  ResTable_config config;
  uint32_t flags = 0u;
  ApkAssetsCookie cookie =
      FindEntry(resid, density_override, false /* stop_at_first_match */, &entry, &config, &flags);
  if (cookie == kInvalidCookie) {
    return kInvalidCookie;
  }

  if (dtohl(entry.entry->flags) & ResTable_entry::FLAG_COMPLEX) {
    if (!may_be_bag) {
      LOG(ERROR) << base::StringPrintf("Resource %08x is a complex map type.", resid);
    }
    return kInvalidCookie;
  }

  const Res_value* device_value = reinterpret_cast<const Res_value*>(
      reinterpret_cast<const uint8_t*>(entry.entry) + dtohs(entry.entry->size));
  out_value->copyFrom_dtoh(*device_value);

  // Convert the package ID to the runtime assigned package ID.
  entry.dynamic_ref_table->lookupResourceValue(out_value);

  *out_selected_config = config;
  *out_flags = flags;
  return cookie;
}

const ResolvedBag* AssetManager2::GetBag(uint32_t resid) {
  ATRACE_CALL();

  auto cached_iter = cached_bags_.find(resid);
  if (cached_iter != cached_bags_.end()) {
    return cached_iter->second.get();
  }

  LoadedArscEntry entry;
  ResTable_config config;
  uint32_t flags = 0u;
  ApkAssetsCookie cookie = FindEntry(resid, 0u /* density_override */,
                                     false /* stop_at_first_match */, &entry, &config, &flags);
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

  uint32_t parent_resid = dtohl(map->parent.ident);
  if (parent_resid == 0) {
    // There is no parent, meaning there is nothing to inherit and we can do a simple
    // copy of the entries in the map.
    const size_t entry_count = map_entry_end - map_entry;
    util::unique_cptr<ResolvedBag> new_bag{reinterpret_cast<ResolvedBag*>(
        malloc(sizeof(ResolvedBag) + (entry_count * sizeof(ResolvedBag::Entry))))};
    ResolvedBag::Entry* new_entry = new_bag->entries;
    for (; map_entry != map_entry_end; ++map_entry) {
      uint32_t new_key = dtohl(map_entry->name.ident);
      if (!util::is_internal_resid(new_key)) {
        // Attributes, arrays, etc don't have a resource id as the name. They specify
        // other data, which would be wrong to change via a lookup.
        if (entry.dynamic_ref_table->lookupResourceId(&new_key) != NO_ERROR) {
          LOG(ERROR) << base::StringPrintf("Failed to resolve key 0x%08x in bag 0x%08x.", new_key, resid);
          return nullptr;
        }
      }
      new_entry->cookie = cookie;
      new_entry->value.copyFrom_dtoh(map_entry->value);
      new_entry->key = new_key;
      new_entry->key_pool = nullptr;
      new_entry->type_pool = nullptr;
      ++new_entry;
    }
    new_bag->type_spec_flags = flags;
    new_bag->entry_count = static_cast<uint32_t>(entry_count);
    ResolvedBag* result = new_bag.get();
    cached_bags_[resid] = std::move(new_bag);
    return result;
  }

  // In case the parent is a dynamic reference, resolve it.
  entry.dynamic_ref_table->lookupResourceId(&parent_resid);

  // Get the parent and do a merge of the keys.
  const ResolvedBag* parent_bag = GetBag(parent_resid);
  if (parent_bag == nullptr) {
    // Failed to get the parent that should exist.
    LOG(ERROR) << base::StringPrintf("Failed to find parent 0x%08x of bag 0x%08x.", parent_resid, resid);
    return nullptr;
  }

  // Combine flags from the parent and our own bag.
  flags |= parent_bag->type_spec_flags;

  // Create the max possible entries we can make. Once we construct the bag,
  // we will realloc to fit to size.
  const size_t max_count = parent_bag->entry_count + dtohl(map->count);
  ResolvedBag* new_bag = reinterpret_cast<ResolvedBag*>(
      malloc(sizeof(ResolvedBag) + (max_count * sizeof(ResolvedBag::Entry))));
  ResolvedBag::Entry* new_entry = new_bag->entries;

  const ResolvedBag::Entry* parent_entry = parent_bag->entries;
  const ResolvedBag::Entry* const parent_entry_end = parent_entry + parent_bag->entry_count;

  // The keys are expected to be in sorted order. Merge the two bags.
  while (map_entry != map_entry_end && parent_entry != parent_entry_end) {
    uint32_t child_key = dtohl(map_entry->name.ident);
    if (!util::is_internal_resid(child_key)) {
      if (entry.dynamic_ref_table->lookupResourceId(&child_key) != NO_ERROR) {
        LOG(ERROR) << base::StringPrintf("Failed to resolve key 0x%08x in bag 0x%08x.", child_key, resid);
        return nullptr;
      }
    }

    if (child_key <= parent_entry->key) {
      // Use the child key if it comes before the parent
      // or is equal to the parent (overrides).
      new_entry->cookie = cookie;
      new_entry->value.copyFrom_dtoh(map_entry->value);
      new_entry->key = child_key;
      new_entry->key_pool = nullptr;
      new_entry->type_pool = nullptr;
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
    if (!util::is_internal_resid(new_key)) {
      if (entry.dynamic_ref_table->lookupResourceId(&new_key) != NO_ERROR) {
        LOG(ERROR) << base::StringPrintf("Failed to resolve key 0x%08x in bag 0x%08x.", new_key, resid);
        return nullptr;
      }
    }
    new_entry->cookie = cookie;
    new_entry->value.copyFrom_dtoh(map_entry->value);
    new_entry->key = new_key;
    new_entry->key_pool = nullptr;
    new_entry->type_pool = nullptr;
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
    new_bag = reinterpret_cast<ResolvedBag*>(
        realloc(new_bag, sizeof(ResolvedBag) + (actual_count * sizeof(ResolvedBag::Entry))));
  }

  util::unique_cptr<ResolvedBag> final_bag{new_bag};
  final_bag->type_spec_flags = flags;
  final_bag->entry_count = static_cast<uint32_t>(actual_count);
  ResolvedBag* result = final_bag.get();
  cached_bags_[resid] = std::move(final_bag);
  return result;
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

std::unique_ptr<Theme> AssetManager2::NewTheme() { return std::unique_ptr<Theme>(new Theme(this)); }

bool Theme::ApplyStyle(uint32_t resid, bool force) {
  ATRACE_CALL();

  const ResolvedBag* bag = asset_manager_->GetBag(resid);
  if (bag == nullptr) {
    return false;
  }

  // Merge the flags from this style.
  type_spec_flags_ |= bag->type_spec_flags;

  // On the first iteration, verify the attribute IDs and
  // update the entry count in each type.
  const auto bag_iter_end = end(bag);
  for (auto bag_iter = begin(bag); bag_iter != bag_iter_end; ++bag_iter) {
    const uint32_t attr_resid = bag_iter->key;

    // If the resource ID passed in is not a style, the key can be
    // some other identifier that is not a resource ID.
    if (!util::is_valid_resid(attr_resid)) {
      return false;
    }

    const uint32_t package_idx = util::get_package_id(attr_resid);

    // The type ID is 1-based, so subtract 1 to get an index.
    const uint32_t type_idx = util::get_type_id(attr_resid) - 1;
    const uint32_t entry_idx = util::get_entry_id(attr_resid);

    std::unique_ptr<Package>& package = packages_[package_idx];
    if (package == nullptr) {
      package.reset(new Package());
    }

    util::unique_cptr<Type>& type = package->types[type_idx];
    if (type == nullptr) {
      // Set the initial capacity to take up a total amount of 1024 bytes.
      constexpr uint32_t kInitialCapacity = (1024u - sizeof(Type)) / sizeof(Entry);
      const uint32_t initial_capacity = std::max(entry_idx, kInitialCapacity);
      type.reset(
          reinterpret_cast<Type*>(calloc(sizeof(Type) + (initial_capacity * sizeof(Entry)), 1)));
      type->entry_capacity = initial_capacity;
    }

    // Set the entry_count to include this entry. We will populate
    // and resize the array as necessary in the next pass.
    if (entry_idx + 1 > type->entry_count) {
      // Increase the entry count to include this.
      type->entry_count = entry_idx + 1;
    }
  }

  // On the second pass, we will realloc to fit the entry counts
  // and populate the structures.
  for (auto bag_iter = begin(bag); bag_iter != bag_iter_end; ++bag_iter) {
    const uint32_t attr_resid = bag_iter->key;
    const uint32_t package_idx = util::get_package_id(attr_resid);
    const uint32_t type_idx = util::get_type_id(attr_resid) - 1;
    const uint32_t entry_idx = util::get_entry_id(attr_resid);
    Package* package = packages_[package_idx].get();
    util::unique_cptr<Type>& type = package->types[type_idx];
    if (type->entry_count != type->entry_capacity) {
      // Resize to fit the actual entries that will be included.
      Type* type_ptr = type.release();
      type.reset(reinterpret_cast<Type*>(
          realloc(type_ptr, sizeof(Type) + (type_ptr->entry_count * sizeof(Entry)))));
      if (type->entry_capacity < type->entry_count) {
        // Clear the newly allocated memory (which does not get zero initialized).
        // We need to do this because we |= type_spec_flags.
        memset(type->entries + type->entry_capacity, 0,
               sizeof(Entry) * (type->entry_count - type->entry_capacity));
      }
      type->entry_capacity = type->entry_count;
    }
    Entry& entry = type->entries[entry_idx];
    if (force || entry.value.dataType == Res_value::TYPE_NULL) {
      entry.cookie = bag_iter->cookie;
      entry.type_spec_flags |= bag->type_spec_flags;
      entry.value = bag_iter->value;
    }
  }
  return true;
}

ApkAssetsCookie Theme::GetAttribute(uint32_t resid, Res_value* out_value,
                                    uint32_t* out_flags) const {
  constexpr const int kMaxIterations = 20;

  uint32_t type_spec_flags = 0u;

  for (int iterations_left = kMaxIterations; iterations_left > 0; iterations_left--) {
    if (!util::is_valid_resid(resid)) {
      return kInvalidCookie;
    }

    const uint32_t package_idx = util::get_package_id(resid);

    // Type ID is 1-based, subtract 1 to get the index.
    const uint32_t type_idx = util::get_type_id(resid) - 1;
    const uint32_t entry_idx = util::get_entry_id(resid);

    const Package* package = packages_[package_idx].get();
    if (package == nullptr) {
      return kInvalidCookie;
    }

    const Type* type = package->types[type_idx].get();
    if (type == nullptr) {
      return kInvalidCookie;
    }

    if (entry_idx >= type->entry_count) {
      return kInvalidCookie;
    }

    const Entry& entry = type->entries[entry_idx];
    type_spec_flags |= entry.type_spec_flags;

    switch (entry.value.dataType) {
      case Res_value::TYPE_NULL:
        return kInvalidCookie;

      case Res_value::TYPE_ATTRIBUTE:
        resid = entry.value.data;
        break;

      case Res_value::TYPE_DYNAMIC_ATTRIBUTE: {
        // Resolve the dynamic attribute to a normal attribute
        // (with the right package ID).
        resid = entry.value.data;
        const DynamicRefTable* ref_table =
            asset_manager_->GetDynamicRefTableForPackage(package_idx);
        if (ref_table == nullptr || ref_table->lookupResourceId(&resid) != NO_ERROR) {
          LOG(ERROR) << base::StringPrintf("Failed to resolve dynamic attribute 0x%08x", resid);
          return kInvalidCookie;
        }
      } break;

      case Res_value::TYPE_DYNAMIC_REFERENCE: {
        // Resolve the dynamic reference to a normal reference
        // (with the right package ID).
        out_value->dataType = Res_value::TYPE_REFERENCE;
        out_value->data = entry.value.data;
        const DynamicRefTable* ref_table =
            asset_manager_->GetDynamicRefTableForPackage(package_idx);
        if (ref_table == nullptr || ref_table->lookupResourceId(&out_value->data) != NO_ERROR) {
          LOG(ERROR) << base::StringPrintf("Failed to resolve dynamic reference 0x%08x",
                                           out_value->data);
          return kInvalidCookie;
        }

        if (out_flags != nullptr) {
          *out_flags = type_spec_flags;
        }
        return entry.cookie;
      }

      default:
        *out_value = entry.value;
        if (out_flags != nullptr) {
          *out_flags = type_spec_flags;
        }
        return entry.cookie;
    }
  }

  LOG(WARNING) << base::StringPrintf("Too many (%d) attribute references, stopped at: 0x%08x",
                                     kMaxIterations, resid);
  return kInvalidCookie;
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

  if (asset_manager_ != o.asset_manager_) {
    return false;
  }

  type_spec_flags_ = o.type_spec_flags_;

  for (size_t p = 0; p < packages_.size(); p++) {
    const Package* package = o.packages_[p].get();
    if (package == nullptr) {
      packages_[p].reset();
      continue;
    }

    for (size_t t = 0; t < package->types.size(); t++) {
      const Type* type = package->types[t].get();
      if (type == nullptr) {
        packages_[p]->types[t].reset();
        continue;
      }

      const size_t type_alloc_size = sizeof(Type) + (type->entry_capacity * sizeof(Entry));
      void* copied_data = malloc(type_alloc_size);
      memcpy(copied_data, type, type_alloc_size);
      packages_[p]->types[t].reset(reinterpret_cast<Type*>(copied_data));
    }
  }
  return true;
}

}  // namespace android
