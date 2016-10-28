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
  if (invalidate_caches) {
    InvalidateCaches(static_cast<uint32_t>(-1));
  }
  return true;
}

const std::vector<const ApkAssets*> AssetManager2::GetApkAssets() const { return apk_assets_; }

const ResStringPool* AssetManager2::GetStringPoolForCookie(ApkAssetsCookie cookie) const {
  if (cookie < 0 || static_cast<size_t>(cookie) >= apk_assets_.size()) {
    return nullptr;
  }
  return apk_assets_[cookie]->GetLoadedArsc()->GetStringPool();
}

void AssetManager2::SetConfiguration(const ResTable_config& configuration) {
  const int diff = configuration_.diff(configuration);
  configuration_ = configuration;

  if (diff) {
    InvalidateCaches(static_cast<uint32_t>(diff));
  }
}

const ResTable_config& AssetManager2::GetConfiguration() const { return configuration_; }

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
                                         bool stop_at_first_match, LoadedArsc::Entry* out_entry,
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

  LoadedArsc::Entry best_entry;
  ResTable_config best_config;
  int32_t best_index = -1;
  uint32_t cumulated_flags = 0;

  const size_t apk_asset_count = apk_assets_.size();
  for (size_t i = 0; i < apk_asset_count; i++) {
    const LoadedArsc* loaded_arsc = apk_assets_[i]->GetLoadedArsc();

    LoadedArsc::Entry current_entry;
    ResTable_config current_config;
    uint32_t flags = 0;
    if (!loaded_arsc->FindEntry(resid, *desired_config, &current_entry, &current_config, &flags)) {
      continue;
    }

    cumulated_flags |= flags;

    if (best_index == -1 || current_config.isBetterThan(best_config, desired_config)) {
      best_entry = current_entry;
      best_config = current_config;
      best_index = static_cast<int32_t>(i);
      if (stop_at_first_match) {
        break;
      }
    }
  }

  if (best_index == -1) {
    return kInvalidCookie;
  }

  *out_entry = best_entry;
  *out_selected_config = best_config;
  *out_flags = cumulated_flags;
  return best_index;
}

bool AssetManager2::GetResourceName(uint32_t resid, ResourceName* out_name) {
  ATRACE_CALL();

  LoadedArsc::Entry entry;
  ResTable_config config;
  uint32_t flags = 0u;
  ApkAssetsCookie cookie = FindEntry(resid, 0u /* density_override */,
                                     true /* stop_at_first_match */, &entry, &config, &flags);
  if (cookie == kInvalidCookie) {
    return false;
  }

  const std::string* package_name =
      apk_assets_[cookie]->GetLoadedArsc()->GetPackageNameForId(resid);
  if (package_name == nullptr) {
    return false;
  }

  out_name->package = package_name->data();
  out_name->package_len = package_name->size();

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
  LoadedArsc::Entry entry;
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

  LoadedArsc::Entry entry;
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

  LoadedArsc::Entry entry;
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

  const uint32_t parent = dtohl(map->parent.ident);
  if (parent == 0) {
    // There is no parent, meaning there is nothing to inherit and we can do a simple
    // copy of the entries in the map.
    const size_t entry_count = map_entry_end - map_entry;
    util::unique_cptr<ResolvedBag> new_bag{reinterpret_cast<ResolvedBag*>(
        malloc(sizeof(ResolvedBag) + (entry_count * sizeof(ResolvedBag::Entry))))};
    ResolvedBag::Entry* new_entry = new_bag->entries;
    for (; map_entry != map_entry_end; ++map_entry) {
      new_entry->cookie = cookie;
      new_entry->value.copyFrom_dtoh(map_entry->value);
      new_entry->key = dtohl(map_entry->name.ident);
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

  // Get the parent and do a merge of the keys.
  const ResolvedBag* parent_bag = GetBag(parent);
  if (parent_bag == nullptr) {
    // Failed to get the parent that should exist.
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
    const uint32_t child_key = dtohl(map_entry->name.ident);
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
    new_entry->cookie = cookie;
    new_entry->value.copyFrom_dtoh(map_entry->value);
    new_entry->key = dtohl(map_entry->name.ident);
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
      case Res_value::TYPE_ATTRIBUTE:
        resid = entry.value.data;
        break;

      case Res_value::TYPE_NULL:
        return kInvalidCookie;

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

  for (size_t p = 0; p < arraysize(packages_); p++) {
    const Package* package = o.packages_[p].get();
    if (package == nullptr) {
      packages_[p].reset();
      continue;
    }

    for (size_t t = 0; t < arraysize(package->types); t++) {
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
