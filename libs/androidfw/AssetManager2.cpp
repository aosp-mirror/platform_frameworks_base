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
#include <map>
#include <set>

#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "androidfw/ResourceUtils.h"
#include "androidfw/Util.h"
#include "utils/ByteOrder.h"
#include "utils/Trace.h"

#ifdef _WIN32
#ifdef ERROR
#undef ERROR
#endif
#endif

namespace android {

namespace {

using EntryValue = std::variant<Res_value, incfs::verified_map_ptr<ResTable_map_entry>>;

base::expected<EntryValue, IOError> GetEntryValue(
    incfs::verified_map_ptr<ResTable_entry> table_entry) {
  const uint16_t entry_size = dtohs(table_entry->size);

  // Check if the entry represents a bag value.
  if (entry_size >= sizeof(ResTable_map_entry) &&
      (dtohs(table_entry->flags) & ResTable_entry::FLAG_COMPLEX)) {
    const auto map_entry = table_entry.convert<ResTable_map_entry>();
    if (!map_entry) {
      return base::unexpected(IOError::PAGES_MISSING);
    }
    return map_entry.verified();
  }

  // The entry represents a non-bag value.
  const auto entry_value = table_entry.offset(entry_size).convert<Res_value>();
  if (!entry_value) {
    return base::unexpected(IOError::PAGES_MISSING);
  }
  Res_value value;
  value.copyFrom_dtoh(entry_value.value());
  return value;
}

} // namespace

struct FindEntryResult {
  // The cookie representing the ApkAssets in which the value resides.
  ApkAssetsCookie cookie;

  // The value of the resource table entry. Either an android::Res_value for non-bag types or an
  // incfs::verified_map_ptr<ResTable_map_entry> for bag types.
  EntryValue entry;

  // The configuration for which the resulting entry was defined. This is already swapped to host
  // endianness.
  ResTable_config config;

  // The bitmask of configuration axis with which the resource value varies.
  uint32_t type_flags;

  // The dynamic package ID map for the package from which this resource came from.
  const DynamicRefTable* dynamic_ref_table;

  // The package name of the resource.
  const std::string* package_name;

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

bool AssetManager2::SetApkAssets(std::vector<const ApkAssets*> apk_assets, bool invalidate_caches) {
  apk_assets_ = std::move(apk_assets);
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

  // A mapping from path of apk assets that could be target packages of overlays to the runtime
  // package id of its first loaded package. Overlays currently can only override resources in the
  // first package in the target resource table.
  std::unordered_map<std::string, uint8_t> target_assets_package_ids;

  // Overlay resources are not directly referenced by an application so their resource ids
  // can change throughout the application's lifetime. Assign overlay package ids last.
  std::vector<const ApkAssets*> sorted_apk_assets(apk_assets_);
  std::stable_partition(sorted_apk_assets.begin(), sorted_apk_assets.end(), [](const ApkAssets* a) {
    return !a->IsOverlay();
  });

  // The assets cookie must map to the position of the apk assets in the unsorted apk assets list.
  std::unordered_map<const ApkAssets*, ApkAssetsCookie> apk_assets_cookies;
  apk_assets_cookies.reserve(apk_assets_.size());
  for (size_t i = 0, n = apk_assets_.size(); i < n; i++) {
    apk_assets_cookies[apk_assets_[i]] = static_cast<ApkAssetsCookie>(i);
  }

  // 0x01 is reserved for the android package.
  int next_package_id = 0x02;
  for (const ApkAssets* apk_assets : sorted_apk_assets) {
    std::shared_ptr<OverlayDynamicRefTable> overlay_ref_table;
    if (auto loaded_idmap = apk_assets->GetLoadedIdmap(); loaded_idmap != nullptr) {
      // The target package must precede the overlay package in the apk assets paths in order
      // to take effect.
      auto iter = target_assets_package_ids.find(std::string(loaded_idmap->TargetApkPath()));
      if (iter == target_assets_package_ids.end()) {
         LOG(INFO) << "failed to find target package for overlay "
                   << loaded_idmap->OverlayApkPath();
      } else {
        uint8_t target_package_id = iter->second;

        // Create a special dynamic reference table for the overlay to rewrite references to
        // overlay resources as references to the target resources they overlay.
        overlay_ref_table = std::make_shared<OverlayDynamicRefTable>(
            loaded_idmap->GetOverlayDynamicRefTable(target_package_id));

        // Add the overlay resource map to the target package's set of overlays.
        const uint8_t target_idx = package_ids_[target_package_id];
        CHECK(target_idx != 0xff) << "overlay target '" << loaded_idmap->TargetApkPath()
                                  << "'added to apk_assets_package_ids but does not have an"
                                  << " assigned package group";

        PackageGroup& target_package_group = package_groups_[target_idx];
        target_package_group.overlays_.push_back(
            ConfiguredOverlay{loaded_idmap->GetTargetResourcesMap(target_package_id,
                                                                  overlay_ref_table.get()),
                              apk_assets_cookies[apk_assets]});
      }
    }

    const LoadedArsc* loaded_arsc = apk_assets->GetLoadedArsc();
    for (const std::unique_ptr<const LoadedPackage>& package : loaded_arsc->GetPackages()) {
      // Get the package ID or assign one if a shared library.
      int package_id;
      if (package->IsDynamic()) {
        package_id = next_package_id++;
      } else {
        package_id = package->GetPackageId();
      }

      uint8_t idx = package_ids_[package_id];
      if (idx == 0xff) {
        // Add the mapping for package ID to index if not present.
        package_ids_[package_id] = idx = static_cast<uint8_t>(package_groups_.size());
        PackageGroup& new_group = package_groups_.emplace_back();

        if (overlay_ref_table != nullptr) {
          // If this package is from an overlay, use a dynamic reference table that can rewrite
          // overlay resource ids to their corresponding target resource ids.
          new_group.dynamic_ref_table = overlay_ref_table;
        }

        DynamicRefTable* ref_table = new_group.dynamic_ref_table.get();
        ref_table->mAssignedPackageId = package_id;
        ref_table->mAppAsLib = package->IsDynamic() && package->GetPackageId() == 0x7f;
      }

      // Add the package and to the set of packages with the same ID.
      PackageGroup* package_group = &package_groups_[idx];
      package_group->packages_.push_back(ConfiguredPackage{package.get(), {}});
      package_group->cookies_.push_back(apk_assets_cookies[apk_assets]);

      // Add the package name -> build time ID mappings.
      for (const DynamicPackageEntry& entry : package->GetDynamicPackageMap()) {
        String16 package_name(entry.package_name.c_str(), entry.package_name.size());
        package_group->dynamic_ref_table->mEntries.replaceValueFor(
            package_name, static_cast<uint8_t>(entry.package_id));
      }

      if (auto apk_assets_path = apk_assets->GetPath()) {
        // Overlay target ApkAssets must have been created using path based load apis.
        target_assets_package_ids.insert(std::make_pair(std::string(*apk_assets_path), package_id));
      }
    }
  }

  // Now assign the runtime IDs so that we have a build-time to runtime ID map.
  const auto package_groups_end = package_groups_.end();
  for (auto iter = package_groups_.begin(); iter != package_groups_end; ++iter) {
    const std::string& package_name = iter->packages_[0].loaded_package_->GetPackageName();
    for (auto iter2 = package_groups_.begin(); iter2 != package_groups_end; ++iter2) {
      iter2->dynamic_ref_table->addMapping(String16(package_name.c_str(), package_name.size()),
                                           iter->dynamic_ref_table->mAssignedPackageId);
    }
  }
}

void AssetManager2::DumpToLog() const {
  base::ScopedLogSeverity _log(base::INFO);

  LOG(INFO) << base::StringPrintf("AssetManager2(this=%p)", this);

  std::string list;
  for (const auto& apk_assets : apk_assets_) {
    base::StringAppendF(&list, "%s,", apk_assets->GetDebugName().c_str());
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
                                    package_group.dynamic_ref_table->mAssignedPackageId)
              << list;

    for (size_t i = 0; i < 256; i++) {
      if (package_group.dynamic_ref_table->mLookupTable[i] != 0) {
        LOG(INFO) << base::StringPrintf("    e[0x%02x] -> 0x%02x", (uint8_t) i,
                                        package_group.dynamic_ref_table->mLookupTable[i]);
      }
    }
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
  return package_groups_[idx].dynamic_ref_table.get();
}

std::shared_ptr<const DynamicRefTable> AssetManager2::GetDynamicRefTableForCookie(
    ApkAssetsCookie cookie) const {
  for (const PackageGroup& package_group : package_groups_) {
    for (const ApkAssetsCookie& package_cookie : package_group.cookies_) {
      if (package_cookie == cookie) {
        return package_group.dynamic_ref_table;
      }
    }
  }
  return nullptr;
}

const std::unordered_map<std::string, std::string>*
  AssetManager2::GetOverlayableMapForPackage(uint32_t package_id) const {

  if (package_id >= package_ids_.size()) {
    return nullptr;
  }

  const size_t idx = package_ids_[package_id];
  if (idx == 0xff) {
    return nullptr;
  }

  const PackageGroup& package_group = package_groups_[idx];
  if (package_group.packages_.empty()) {
    return nullptr;
  }

  const auto loaded_package = package_group.packages_[0].loaded_package_;
  return &loaded_package->GetOverlayableMap();
}

bool AssetManager2::GetOverlayablesToString(const android::StringPiece& package_name,
                                            std::string* out) const {
  uint8_t package_id = 0U;
  for (const auto& apk_assets : apk_assets_) {
    const LoadedArsc* loaded_arsc = apk_assets->GetLoadedArsc();
    if (loaded_arsc == nullptr) {
      continue;
    }

    const auto& loaded_packages = loaded_arsc->GetPackages();
    if (loaded_packages.empty()) {
      continue;
    }

    const auto& loaded_package = loaded_packages[0];
    if (loaded_package->GetPackageName() == package_name) {
      package_id = GetAssignedPackageId(loaded_package.get());
      break;
    }
  }

  if (package_id == 0U) {
    ANDROID_LOG(ERROR) << base::StringPrintf("No package with name '%s", package_name.data());
    return false;
  }

  const size_t idx = package_ids_[package_id];
  if (idx == 0xff) {
    return false;
  }

  std::string output;
  for (const ConfiguredPackage& package : package_groups_[idx].packages_) {
    const LoadedPackage* loaded_package = package.loaded_package_;
    for (auto it = loaded_package->begin(); it != loaded_package->end(); it++) {
      const OverlayableInfo* info = loaded_package->GetOverlayableInfo(*it);
      if (info != nullptr) {
        auto res_name = GetResourceName(*it);
        if (!res_name.has_value()) {
          ANDROID_LOG(ERROR) << base::StringPrintf(
              "Unable to retrieve name of overlayable resource 0x%08x", *it);
          return false;
        }

        const std::string name = ToFormattedResourceString(*res_name);
        output.append(base::StringPrintf(
            "resource='%s' overlayable='%s' actor='%s' policy='0x%08x'\n",
            name.c_str(), info->name.c_str(), info->actor.c_str(), info->policy_flags));
      }
    }
  }

  *out = std::move(output);
  return true;
}

bool AssetManager2::ContainsAllocatedTable() const {
  return std::find_if(apk_assets_.begin(), apk_assets_.end(),
                      std::mem_fn(&ApkAssets::IsTableAllocated)) != apk_assets_.end();
}

void AssetManager2::SetConfiguration(const ResTable_config& configuration) {
  const int diff = configuration_.diff(configuration);
  configuration_ = configuration;

  if (diff) {
    RebuildFilterList();
    InvalidateCaches(static_cast<uint32_t>(diff));
  }
}

std::set<const ApkAssets*> AssetManager2::GetNonSystemOverlays() const {
  std::set<const ApkAssets*> non_system_overlays;
  for (const PackageGroup& package_group : package_groups_) {
    bool found_system_package = false;
    for (const ConfiguredPackage& package : package_group.packages_) {
      if (package.loaded_package_->IsSystem()) {
        found_system_package = true;
        break;
      }
    }

    if (!found_system_package) {
      for (const ConfiguredOverlay& overlay : package_group.overlays_) {
        non_system_overlays.insert(apk_assets_[overlay.cookie]);
      }
    }
  }

  return non_system_overlays;
}

base::expected<std::set<ResTable_config>, IOError> AssetManager2::GetResourceConfigurations(
    bool exclude_system, bool exclude_mipmap) const {
  ATRACE_NAME("AssetManager::GetResourceConfigurations");
  const auto non_system_overlays =
      (exclude_system) ? GetNonSystemOverlays() : std::set<const ApkAssets*>();

  std::set<ResTable_config> configurations;
  for (const PackageGroup& package_group : package_groups_) {
    for (size_t i = 0; i < package_group.packages_.size(); i++) {
      const ConfiguredPackage& package = package_group.packages_[i];
      if (exclude_system && package.loaded_package_->IsSystem()) {
        continue;
      }

      auto apk_assets = apk_assets_[package_group.cookies_[i]];
      if (exclude_system && apk_assets->IsOverlay() &&
          non_system_overlays.find(apk_assets) == non_system_overlays.end()) {
        // Exclude overlays that target system resources.
        continue;
      }

      auto result = package.loaded_package_->CollectConfigurations(exclude_mipmap, &configurations);
      if (UNLIKELY(!result.has_value())) {
        return base::unexpected(result.error());
      }
    }
  }
  return configurations;
}

std::set<std::string> AssetManager2::GetResourceLocales(bool exclude_system,
                                                        bool merge_equivalent_languages) const {
  ATRACE_NAME("AssetManager::GetResourceLocales");
  std::set<std::string> locales;
  const auto non_system_overlays =
      (exclude_system) ? GetNonSystemOverlays() : std::set<const ApkAssets*>();

  for (const PackageGroup& package_group : package_groups_) {
    for (size_t i = 0; i < package_group.packages_.size(); i++) {
      const ConfiguredPackage& package = package_group.packages_[i];
      if (exclude_system && package.loaded_package_->IsSystem()) {
        continue;
      }

      auto apk_assets = apk_assets_[package_group.cookies_[i]];
      if (exclude_system && apk_assets->IsOverlay() &&
          non_system_overlays.find(apk_assets) == non_system_overlays.end()) {
        // Exclude overlays that target system resources.
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
    if (apk_assets->IsOverlay()) {
      continue;
    }

    auto func = [&](const StringPiece& name, FileType type) {
      AssetDir::FileInfo info;
      info.setFileName(String8(name.data(), name.size()));
      info.setFileType(type);
      info.setSourceName(String8(apk_assets->GetDebugName().c_str()));
      files->add(info);
    };

    if (!apk_assets->GetAssetsProvider()->ForEachFile(full_path, func)) {
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
    // Prevent RRO from modifying assets and other entries accessed by file
    // path. Explicitly asking for a path in a given package (denoted by a
    // cookie) is still OK.
    if (apk_assets_[i]->IsOverlay()) {
      continue;
    }

    std::unique_ptr<Asset> asset = apk_assets_[i]->GetAssetsProvider()->Open(filename, mode);
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
  return apk_assets_[cookie]->GetAssetsProvider()->Open(filename, mode);
}

base::expected<FindEntryResult, NullOrIOError> AssetManager2::FindEntry(
    uint32_t resid, uint16_t density_override, bool stop_at_first_match,
    bool ignore_configuration) const {
  const bool logging_enabled = resource_resolution_logging_enabled_;
  if (UNLIKELY(logging_enabled)) {
    // Clear the last logged resource resolution.
    ResetResourceResolution();
    last_resolution_.resid = resid;
  }

  // Might use this if density_override != 0.
  ResTable_config density_override_config;

  // Select our configuration or generate a density override configuration.
  const ResTable_config* desired_config = &configuration_;
  if (density_override != 0 && density_override != configuration_.density) {
    density_override_config = configuration_;
    density_override_config.density = density_override;
    desired_config = &density_override_config;
  }

  // Retrieve the package group from the package id of the resource id.
  if (UNLIKELY(!is_valid_resid(resid))) {
    LOG(ERROR) << base::StringPrintf("Invalid ID 0x%08x.", resid);
    return base::unexpected(std::nullopt);
  }

  const uint32_t package_id = get_package_id(resid);
  const uint8_t type_idx = get_type_id(resid) - 1;
  const uint16_t entry_idx = get_entry_id(resid);
  uint8_t package_idx = package_ids_[package_id];
  if (UNLIKELY(package_idx == 0xff)) {
    ANDROID_LOG(ERROR) << base::StringPrintf("No package ID %02x found for ID 0x%08x.",
                                             package_id, resid);
    return base::unexpected(std::nullopt);
  }

  const PackageGroup& package_group = package_groups_[package_idx];
  auto result = FindEntryInternal(package_group, type_idx, entry_idx, *desired_config,
                                  stop_at_first_match, ignore_configuration);
  if (UNLIKELY(!result.has_value())) {
    return base::unexpected(result.error());
  }

  if (!stop_at_first_match && !ignore_configuration && !apk_assets_[result->cookie]->IsLoader()) {
    for (const auto& id_map : package_group.overlays_) {
      auto overlay_entry = id_map.overlay_res_maps_.Lookup(resid);
      if (!overlay_entry) {
        // No id map entry exists for this target resource.
        continue;
      }
      if (overlay_entry.IsInlineValue()) {
        // The target resource is overlaid by an inline value not represented by a resource.
        result->entry = overlay_entry.GetInlineValue();
        result->dynamic_ref_table = id_map.overlay_res_maps_.GetOverlayDynamicRefTable();
        result->cookie = id_map.cookie;
        continue;
      }

      auto overlay_result = FindEntry(overlay_entry.GetResourceId(), density_override,
                                      false /* stop_at_first_match */,
                                      false /* ignore_configuration */);
      if (UNLIKELY(IsIOError(overlay_result))) {
        return base::unexpected(overlay_result.error());
      }
      if (!overlay_result.has_value()) {
        continue;
      }

      if (!overlay_result->config.isBetterThan(result->config, desired_config)
          && overlay_result->config.compare(result->config) != 0) {
        // The configuration of the entry for the overlay must be equal to or better than the target
        // configuration to be chosen as the better value.
        continue;
      }

      result->cookie = overlay_result->cookie;
      result->entry = overlay_result->entry;
      result->config = overlay_result->config;
      result->dynamic_ref_table = id_map.overlay_res_maps_.GetOverlayDynamicRefTable();

      if (UNLIKELY(logging_enabled)) {
        last_resolution_.steps.push_back(
            Resolution::Step{Resolution::Step::Type::OVERLAID, overlay_result->config.toString(),
                             overlay_result->package_name,
                             overlay_result->cookie});
      }
    }
  }

  if (UNLIKELY(logging_enabled)) {
    last_resolution_.cookie = result->cookie;
    last_resolution_.type_string_ref = result->type_string_ref;
    last_resolution_.entry_string_ref = result->entry_string_ref;
  }

  return result;
}

base::expected<FindEntryResult, NullOrIOError> AssetManager2::FindEntryInternal(
    const PackageGroup& package_group, uint8_t type_idx, uint16_t entry_idx,
    const ResTable_config& desired_config, bool stop_at_first_match,
    bool ignore_configuration) const {
  const bool logging_enabled = resource_resolution_logging_enabled_;
  ApkAssetsCookie best_cookie = kInvalidCookie;
  const LoadedPackage* best_package = nullptr;
  incfs::verified_map_ptr<ResTable_type> best_type;
  const ResTable_config* best_config = nullptr;
  uint32_t best_offset = 0U;
  uint32_t type_flags = 0U;

  std::vector<Resolution::Step> resolution_steps;

  // If `desired_config` is not the same as the set configuration or the caller will accept a value
  // from any configuration, then we cannot use our filtered list of types since it only it contains
  // types matched to the set configuration.
  const bool use_filtered = !ignore_configuration && &desired_config == &configuration_;

  const size_t package_count = package_group.packages_.size();
  for (size_t pi = 0; pi < package_count; pi++) {
    const ConfiguredPackage& loaded_package_impl = package_group.packages_[pi];
    const LoadedPackage* loaded_package = loaded_package_impl.loaded_package_;
    const ApkAssetsCookie cookie = package_group.cookies_[pi];

    // If the type IDs are offset in this package, we need to take that into account when searching
    // for a type.
    const TypeSpec* type_spec = loaded_package->GetTypeSpecByTypeIndex(type_idx);
    if (UNLIKELY(type_spec == nullptr)) {
      continue;
    }

    // Allow custom loader packages to overlay resource values with configurations equivalent to the
    // current best configuration.
    const bool package_is_loader = loaded_package->IsCustomLoader();

    auto entry_flags = type_spec->GetFlagsForEntryIndex(entry_idx);
    if (UNLIKELY(!entry_flags.has_value())) {
      return base::unexpected(entry_flags.error());
    }
    type_flags |= entry_flags.value();

    const FilteredConfigGroup& filtered_group = loaded_package_impl.filtered_configs_[type_idx];
    const size_t type_entry_count = (use_filtered) ? filtered_group.type_entries.size()
                                                   : type_spec->type_entries.size();
    for (size_t i = 0; i < type_entry_count; i++) {
      const TypeSpec::TypeEntry* type_entry = (use_filtered) ? filtered_group.type_entries[i]
                                                             : &type_spec->type_entries[i];

      // We can skip calling ResTable_config::match() if the caller does not care for the
      // configuration to match or if we're using the list of types that have already had their
      // configuration matched.
      const ResTable_config& this_config = type_entry->config;
      if (!(use_filtered || ignore_configuration || this_config.match(desired_config))) {
        continue;
      }

      Resolution::Step::Type resolution_type;
      if (best_config == nullptr) {
        resolution_type = Resolution::Step::Type::INITIAL;
      } else if (this_config.isBetterThan(*best_config, &desired_config)) {
        resolution_type = Resolution::Step::Type::BETTER_MATCH;
      } else if (package_is_loader && this_config.compare(*best_config) == 0) {
        resolution_type = Resolution::Step::Type::OVERLAID;
      } else {
        if (UNLIKELY(logging_enabled)) {
          resolution_steps.push_back(Resolution::Step{Resolution::Step::Type::SKIPPED,
                                                      this_config.toString(),
                                                      &loaded_package->GetPackageName(),
                                                      cookie});
        }
        continue;
      }

      // The configuration matches and is better than the previous selection.
      // Find the entry value if it exists for this configuration.
      const auto& type = type_entry->type;
      const auto offset = LoadedPackage::GetEntryOffset(type, entry_idx);
      if (UNLIKELY(IsIOError(offset))) {
        return base::unexpected(offset.error());
      }

      if (!offset.has_value()) {
        if (UNLIKELY(logging_enabled)) {
          resolution_steps.push_back(Resolution::Step{Resolution::Step::Type::NO_ENTRY,
                                                      this_config.toString(),
                                                      &loaded_package->GetPackageName(),
                                                      cookie});
        }
        continue;
      }

      best_cookie = cookie;
      best_package = loaded_package;
      best_type = type;
      best_config = &this_config;
      best_offset = offset.value();

      if (UNLIKELY(logging_enabled)) {
        last_resolution_.steps.push_back(Resolution::Step{resolution_type,
                                                          this_config.toString(),
                                                          &loaded_package->GetPackageName(),
                                                          cookie});
      }

      // Any configuration will suffice, so break.
      if (stop_at_first_match) {
        break;
      }
    }
  }

  if (UNLIKELY(best_cookie == kInvalidCookie)) {
    return base::unexpected(std::nullopt);
  }

  auto best_entry_result = LoadedPackage::GetEntryFromOffset(best_type, best_offset);
  if (!best_entry_result.has_value()) {
    return base::unexpected(best_entry_result.error());
  }

  const incfs::map_ptr<ResTable_entry> best_entry = *best_entry_result;
  if (!best_entry) {
    return base::unexpected(IOError::PAGES_MISSING);
  }

  const auto entry = GetEntryValue(best_entry.verified());
  if (!entry.has_value()) {
    return base::unexpected(entry.error());
  }

  return FindEntryResult{
    .cookie = best_cookie,
    .entry = *entry,
    .config = *best_config,
    .type_flags = type_flags,
    .package_name = &best_package->GetPackageName(),
    .type_string_ref = StringPoolRef(best_package->GetTypeStringPool(), best_type->id - 1),
    .entry_string_ref = StringPoolRef(best_package->GetKeyStringPool(),
                                      best_entry->key.index),
    .dynamic_ref_table = package_group.dynamic_ref_table.get(),
  };
}

void AssetManager2::ResetResourceResolution() const {
  last_resolution_.cookie = kInvalidCookie;
  last_resolution_.resid = 0;
  last_resolution_.steps.clear();
  last_resolution_.type_string_ref = StringPoolRef();
  last_resolution_.entry_string_ref = StringPoolRef();
}

void AssetManager2::SetResourceResolutionLoggingEnabled(bool enabled) {
  resource_resolution_logging_enabled_ = enabled;
  if (!enabled) {
    ResetResourceResolution();
  }
}

std::string AssetManager2::GetLastResourceResolution() const {
  if (!resource_resolution_logging_enabled_) {
    LOG(ERROR) << "Must enable resource resolution logging before getting path.";
    return {};
  }

  const ApkAssetsCookie cookie = last_resolution_.cookie;
  if (cookie == kInvalidCookie) {
    LOG(ERROR) << "AssetManager hasn't resolved a resource to read resolution path.";
    return {};
  }

  const uint32_t resid = last_resolution_.resid;
  const auto package = apk_assets_[cookie]->GetLoadedArsc()->GetPackageById(get_package_id(resid));

  std::string resource_name_string;
  if (package != nullptr) {
    auto resource_name = ToResourceName(last_resolution_.type_string_ref,
                                        last_resolution_.entry_string_ref,
                                        package->GetPackageName());
    resource_name_string = resource_name.has_value() ?
        ToFormattedResourceString(resource_name.value()) : "<unknown>";
  }

  std::stringstream log_stream;
  log_stream << base::StringPrintf("Resolution for 0x%08x ", resid)
            << resource_name_string
            << "\n\tFor config -"
            << configuration_.toString();

  for (const Resolution::Step& step : last_resolution_.steps) {
    const static std::unordered_map<Resolution::Step::Type, const char*> kStepStrings = {
        {Resolution::Step::Type::INITIAL, "Found initial"},
        {Resolution::Step::Type::BETTER_MATCH, "Found better"},
        {Resolution::Step::Type::OVERLAID, "Overlaid"},
        {Resolution::Step::Type::SKIPPED, "Skipped"},
        {Resolution::Step::Type::NO_ENTRY, "No entry"}
    };

    const auto prefix = kStepStrings.find(step.type);
    if (prefix == kStepStrings.end()) {
      continue;
    }

    log_stream << "\n\t" << prefix->second << ": " << *step.package_name << " ("
               << apk_assets_[step.cookie]->GetDebugName() << ")";
    if (!step.config_name.isEmpty()) {
      log_stream << " -" << step.config_name;
    }
  }

  return log_stream.str();
}

base::expected<AssetManager2::ResourceName, NullOrIOError> AssetManager2::GetResourceName(
    uint32_t resid) const {
  auto result = FindEntry(resid, 0u /* density_override */, true /* stop_at_first_match */,
                          true /* ignore_configuration */);
  if (!result.has_value()) {
    return base::unexpected(result.error());
  }

  return ToResourceName(result->type_string_ref,
                        result->entry_string_ref,
                        *result->package_name);
}

base::expected<uint32_t, NullOrIOError> AssetManager2::GetResourceTypeSpecFlags(
    uint32_t resid) const {
  auto result = FindEntry(resid, 0u /* density_override */, false /* stop_at_first_match */,
                          true /* ignore_configuration */);
  if (!result.has_value()) {
    return base::unexpected(result.error());
  }
  return result->type_flags;
}

base::expected<AssetManager2::SelectedValue, NullOrIOError> AssetManager2::GetResource(
    uint32_t resid, bool may_be_bag, uint16_t density_override) const {
  auto result = FindEntry(resid, density_override, false /* stop_at_first_match */,
                          false /* ignore_configuration */);
  if (!result.has_value()) {
    return base::unexpected(result.error());
  }

  auto result_map_entry = std::get_if<incfs::verified_map_ptr<ResTable_map_entry>>(&result->entry);
  if (result_map_entry != nullptr) {
    if (!may_be_bag) {
      LOG(ERROR) << base::StringPrintf("Resource %08x is a complex map type.", resid);
      return base::unexpected(std::nullopt);
    }

    // Create a reference since we can't represent this complex type as a Res_value.
    return SelectedValue(Res_value::TYPE_REFERENCE, resid, result->cookie, result->type_flags,
                         resid, result->config);
  }

  // Convert the package ID to the runtime assigned package ID.
  Res_value value = std::get<Res_value>(result->entry);
  result->dynamic_ref_table->lookupResourceValue(&value);

  return SelectedValue(value.dataType, value.data, result->cookie, result->type_flags,
                       resid, result->config);
}

base::expected<std::monostate, NullOrIOError> AssetManager2::ResolveReference(
    AssetManager2::SelectedValue& value, bool cache_value) const {
  if (value.type != Res_value::TYPE_REFERENCE || value.data == 0U) {
    // Not a reference. Nothing to do.
    return {};
  }

  const uint32_t original_flags = value.flags;
  const uint32_t original_resid = value.data;
  if (cache_value) {
    auto cached_value = cached_resolved_values_.find(value.data);
    if (cached_value != cached_resolved_values_.end()) {
      value = cached_value->second;
      value.flags |= original_flags;
      return {};
    }
  }

  uint32_t combined_flags = 0U;
  uint32_t resolve_resid = original_resid;
  constexpr const uint32_t kMaxIterations = 20;
  for (uint32_t i = 0U;; i++) {
    auto result = GetResource(resolve_resid, true /*may_be_bag*/);
    if (!result.has_value()) {
      value.resid = resolve_resid;
      return base::unexpected(result.error());
    }

    // If resource resolution fails, the value should be set to the last reference that was able to
    // be resolved successfully.
    value = *result;
    value.flags |= combined_flags;

    if (result->type != Res_value::TYPE_REFERENCE ||
        result->data == Res_value::DATA_NULL_UNDEFINED ||
        result->data == resolve_resid || i == kMaxIterations) {
      // This reference can't be resolved, so exit now and let the caller deal with it.
      if (cache_value) {
        cached_resolved_values_[original_resid] = value;
      }

      // Above value is cached without original_flags to ensure they don't get included in future
      // queries that hit the cache
      value.flags |= original_flags;
      return {};
    }

    combined_flags = result->flags;
    resolve_resid = result->data;
  }
}

const std::vector<uint32_t> AssetManager2::GetBagResIdStack(uint32_t resid) const {
  auto cached_iter = cached_bag_resid_stacks_.find(resid);
  if (cached_iter != cached_bag_resid_stacks_.end()) {
    return cached_iter->second;
  }

  std::vector<uint32_t> found_resids;
  GetBag(resid, found_resids);
  cached_bag_resid_stacks_.emplace(resid, found_resids);
  return found_resids;
}

base::expected<const ResolvedBag*, NullOrIOError> AssetManager2::ResolveBag(
    AssetManager2::SelectedValue& value) const {
  if (UNLIKELY(value.type != Res_value::TYPE_REFERENCE)) {
    return base::unexpected(std::nullopt);
  }

  auto bag = GetBag(value.data);
  if (bag.has_value()) {
    value.flags |= (*bag)->type_spec_flags;
  }
  return bag;
}

base::expected<const ResolvedBag*, NullOrIOError> AssetManager2::GetBag(uint32_t resid) const {
  std::vector<uint32_t> found_resids;
  const auto bag = GetBag(resid, found_resids);
  cached_bag_resid_stacks_.emplace(resid, found_resids);
  return bag;
}

base::expected<const ResolvedBag*, NullOrIOError> AssetManager2::GetBag(
    uint32_t resid, std::vector<uint32_t>& child_resids) const {
  if (auto cached_iter = cached_bags_.find(resid); cached_iter != cached_bags_.end()) {
    return cached_iter->second.get();
  }

  auto entry = FindEntry(resid, 0u /* density_override */, false /* stop_at_first_match */,
                         false /* ignore_configuration */);
  if (!entry.has_value()) {
    return base::unexpected(entry.error());
  }

  auto entry_map = std::get_if<incfs::verified_map_ptr<ResTable_map_entry>>(&entry->entry);
  if (entry_map == nullptr) {
    // Not a bag, nothing to do.
    return base::unexpected(std::nullopt);
  }

  auto map = *entry_map;
  auto map_entry = map.offset(dtohs(map->size)).convert<ResTable_map>();
  const auto map_entry_end = map_entry + dtohl(map->count);

  // Keep track of ids that have already been seen to prevent infinite loops caused by circular
  // dependencies between bags.
  child_resids.push_back(resid);

  uint32_t parent_resid = dtohl(map->parent.ident);
  if (parent_resid == 0U ||
      std::find(child_resids.begin(), child_resids.end(), parent_resid) != child_resids.end()) {
    // There is no parent or a circular parental dependency exist, meaning there is nothing to
    // inherit and we can do a simple copy of the entries in the map.
    const size_t entry_count = map_entry_end - map_entry;
    util::unique_cptr<ResolvedBag> new_bag{reinterpret_cast<ResolvedBag*>(
        malloc(sizeof(ResolvedBag) + (entry_count * sizeof(ResolvedBag::Entry))))};

    bool sort_entries = false;
    for (auto new_entry = new_bag->entries; map_entry != map_entry_end; ++map_entry) {
      if (UNLIKELY(!map_entry)) {
        return base::unexpected(IOError::PAGES_MISSING);
      }

      uint32_t new_key = dtohl(map_entry->name.ident);
      if (!is_internal_resid(new_key)) {
        // Attributes, arrays, etc don't have a resource id as the name. They specify
        // other data, which would be wrong to change via a lookup.
        if (UNLIKELY(entry->dynamic_ref_table->lookupResourceId(&new_key) != NO_ERROR)) {
          LOG(ERROR) << base::StringPrintf("Failed to resolve key 0x%08x in bag 0x%08x.", new_key,
                                           resid);
          return base::unexpected(std::nullopt);
        }
      }

      new_entry->cookie = entry->cookie;
      new_entry->key = new_key;
      new_entry->key_pool = nullptr;
      new_entry->type_pool = nullptr;
      new_entry->style = resid;
      new_entry->value.copyFrom_dtoh(map_entry->value);
      status_t err = entry->dynamic_ref_table->lookupResourceValue(&new_entry->value);
      if (UNLIKELY(err != NO_ERROR)) {
        LOG(ERROR) << base::StringPrintf(
            "Failed to resolve value t=0x%02x d=0x%08x for key 0x%08x.", new_entry->value.dataType,
            new_entry->value.data, new_key);
        return base::unexpected(std::nullopt);
      }

      sort_entries = sort_entries ||
          (new_entry != new_bag->entries && (new_entry->key < (new_entry - 1U)->key));
      ++new_entry;
    }

    if (sort_entries) {
      std::sort(new_bag->entries, new_bag->entries + entry_count,
                [](auto&& lhs, auto&& rhs) { return lhs.key < rhs.key; });
    }

    new_bag->type_spec_flags = entry->type_flags;
    new_bag->entry_count = static_cast<uint32_t>(entry_count);
    ResolvedBag* result = new_bag.get();
    cached_bags_[resid] = std::move(new_bag);
    return result;
  }

  // In case the parent is a dynamic reference, resolve it.
  entry->dynamic_ref_table->lookupResourceId(&parent_resid);

  // Get the parent and do a merge of the keys.
  const auto parent_bag = GetBag(parent_resid, child_resids);
  if (UNLIKELY(!parent_bag.has_value())) {
    // Failed to get the parent that should exist.
    LOG(ERROR) << base::StringPrintf("Failed to find parent 0x%08x of bag 0x%08x.", parent_resid,
                                     resid);
    return base::unexpected(parent_bag.error());
  }

  // Create the max possible entries we can make. Once we construct the bag,
  // we will realloc to fit to size.
  const size_t max_count = (*parent_bag)->entry_count + dtohl(map->count);
  util::unique_cptr<ResolvedBag> new_bag{reinterpret_cast<ResolvedBag*>(
      malloc(sizeof(ResolvedBag) + (max_count * sizeof(ResolvedBag::Entry))))};
  ResolvedBag::Entry* new_entry = new_bag->entries;

  const ResolvedBag::Entry* parent_entry = (*parent_bag)->entries;
  const ResolvedBag::Entry* const parent_entry_end = parent_entry + (*parent_bag)->entry_count;

  // The keys are expected to be in sorted order. Merge the two bags.
  bool sort_entries = false;
  while (map_entry != map_entry_end && parent_entry != parent_entry_end) {
    if (UNLIKELY(!map_entry)) {
      return base::unexpected(IOError::PAGES_MISSING);
    }

    uint32_t child_key = dtohl(map_entry->name.ident);
    if (!is_internal_resid(child_key)) {
      if (UNLIKELY(entry->dynamic_ref_table->lookupResourceId(&child_key) != NO_ERROR)) {
        LOG(ERROR) << base::StringPrintf("Failed to resolve key 0x%08x in bag 0x%08x.", child_key,
                                         resid);
        return base::unexpected(std::nullopt);
      }
    }

    if (child_key <= parent_entry->key) {
      // Use the child key if it comes before the parent
      // or is equal to the parent (overrides).
      new_entry->cookie = entry->cookie;
      new_entry->key = child_key;
      new_entry->key_pool = nullptr;
      new_entry->type_pool = nullptr;
      new_entry->value.copyFrom_dtoh(map_entry->value);
      new_entry->style = resid;
      status_t err = entry->dynamic_ref_table->lookupResourceValue(&new_entry->value);
      if (UNLIKELY(err != NO_ERROR)) {
        LOG(ERROR) << base::StringPrintf(
            "Failed to resolve value t=0x%02x d=0x%08x for key 0x%08x.", new_entry->value.dataType,
            new_entry->value.data, child_key);
        return base::unexpected(std::nullopt);
      }
      ++map_entry;
    } else {
      // Take the parent entry as-is.
      memcpy(new_entry, parent_entry, sizeof(*new_entry));
    }

    sort_entries = sort_entries ||
        (new_entry != new_bag->entries && (new_entry->key < (new_entry - 1U)->key));
    if (child_key >= parent_entry->key) {
      // Move to the next parent entry if we used it or it was overridden.
      ++parent_entry;
    }
    // Increment to the next entry to fill.
    ++new_entry;
  }

  // Finish the child entries if they exist.
  while (map_entry != map_entry_end) {
    if (UNLIKELY(!map_entry)) {
      return base::unexpected(IOError::PAGES_MISSING);
    }

    uint32_t new_key = dtohl(map_entry->name.ident);
    if (!is_internal_resid(new_key)) {
      if (UNLIKELY(entry->dynamic_ref_table->lookupResourceId(&new_key) != NO_ERROR)) {
        LOG(ERROR) << base::StringPrintf("Failed to resolve key 0x%08x in bag 0x%08x.", new_key,
                                         resid);
        return base::unexpected(std::nullopt);
      }
    }
    new_entry->cookie = entry->cookie;
    new_entry->key = new_key;
    new_entry->key_pool = nullptr;
    new_entry->type_pool = nullptr;
    new_entry->value.copyFrom_dtoh(map_entry->value);
    new_entry->style = resid;
    status_t err = entry->dynamic_ref_table->lookupResourceValue(&new_entry->value);
    if (UNLIKELY(err != NO_ERROR)) {
      LOG(ERROR) << base::StringPrintf("Failed to resolve value t=0x%02x d=0x%08x for key 0x%08x.",
                                       new_entry->value.dataType, new_entry->value.data, new_key);
      return base::unexpected(std::nullopt);
    }
    sort_entries = sort_entries ||
        (new_entry != new_bag->entries && (new_entry->key < (new_entry - 1U)->key));
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

  if (sort_entries) {
    std::sort(new_bag->entries, new_bag->entries + actual_count,
              [](auto&& lhs, auto&& rhs) { return lhs.key < rhs.key; });
  }

  // Combine flags from the parent and our own bag.
  new_bag->type_spec_flags = entry->type_flags | (*parent_bag)->type_spec_flags;
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

base::expected<uint32_t, NullOrIOError> AssetManager2::GetResourceId(
    const std::string& resource_name, const std::string& fallback_type,
    const std::string& fallback_package) const {
  StringPiece package_name, type, entry;
  if (!ExtractResourceName(resource_name, &package_name, &type, &entry)) {
    return base::unexpected(std::nullopt);
  }

  if (entry.empty()) {
    return base::unexpected(std::nullopt);
  }

  if (package_name.empty()) {
    package_name = fallback_package;
  }

  if (type.empty()) {
    type = fallback_type;
  }

  std::u16string type16;
  if (!Utf8ToUtf16(type, &type16)) {
    return base::unexpected(std::nullopt);
  }

  std::u16string entry16;
  if (!Utf8ToUtf16(entry, &entry16)) {
    return base::unexpected(std::nullopt);
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

      base::expected<uint32_t, NullOrIOError> resid = package->FindEntryByName(type16, entry16);
      if (UNLIKELY(IsIOError(resid))) {
         return base::unexpected(resid.error());
       }

      if (!resid.has_value() && kAttr16 == type16) {
        // Private attributes in libraries (such as the framework) are sometimes encoded
        // under the type '^attr-private' in order to leave the ID space of public 'attr'
        // free for future additions. Check '^attr-private' for the same name.
        resid = package->FindEntryByName(kAttrPrivate16, entry16);
      }

      if (resid.has_value()) {
        return fix_package_id(*resid, package_group.dynamic_ref_table->mAssignedPackageId);
      }
    }
  }
  return base::unexpected(std::nullopt);
}

void AssetManager2::RebuildFilterList() {
  for (PackageGroup& group : package_groups_) {
    for (ConfiguredPackage& impl : group.packages_) {
      // Destroy it.
      impl.filtered_configs_.~ByteBucketArray();

      // Re-create it.
      new (&impl.filtered_configs_) ByteBucketArray<FilteredConfigGroup>();

      // Create the filters here.
      impl.loaded_package_->ForEachTypeSpec([&](const TypeSpec& type_spec, uint8_t type_id) {
        FilteredConfigGroup& group = impl.filtered_configs_.editItemAt(type_id - 1);
        for (const auto& type_entry : type_spec.type_entries) {
          if (type_entry.config.match(configuration_)) {
            group.type_entries.push_back(&type_entry);
          }
        }
      });
    }
  }
}

void AssetManager2::InvalidateCaches(uint32_t diff) {
  cached_bag_resid_stacks_.clear();

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

  cached_resolved_values_.clear();
}

uint8_t AssetManager2::GetAssignedPackageId(const LoadedPackage* package) const {
  for (auto& package_group : package_groups_) {
    for (auto& package2 : package_group.packages_) {
      if (package2.loaded_package_ == package) {
        return package_group.dynamic_ref_table->mAssignedPackageId;
      }
    }
  }
  return 0;
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

base::expected<std::monostate, NullOrIOError> Theme::ApplyStyle(uint32_t resid, bool force) {
  ATRACE_NAME("Theme::ApplyStyle");

  auto bag = asset_manager_->GetBag(resid);
  if (!bag.has_value()) {
    return base::unexpected(bag.error());
  }

  // Merge the flags from this style.
  type_spec_flags_ |= (*bag)->type_spec_flags;

  int last_type_idx = -1;
  int last_package_idx = -1;
  Package* last_package = nullptr;
  ThemeType* last_type = nullptr;

  // Iterate backwards, because each bag is sorted in ascending key ID order, meaning we will only
  // need to perform one resize per type.
  using reverse_bag_iterator = std::reverse_iterator<const ResolvedBag::Entry*>;
  const auto rbegin = reverse_bag_iterator(begin(*bag));
  for (auto it = reverse_bag_iterator(end(*bag)); it != rbegin; ++it) {
    const uint32_t attr_resid = it->key;

    // If the resource ID passed in is not a style, the key can be some other identifier that is not
    // a resource ID. We should fail fast instead of operating with strange resource IDs.
    if (!is_valid_resid(attr_resid)) {
      return base::unexpected(std::nullopt);
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
      entry.cookie = it->cookie;
      entry.type_spec_flags |= (*bag)->type_spec_flags;
      entry.value = it->value;
    }
  }
  return {};
}

std::optional<AssetManager2::SelectedValue> Theme::GetAttribute(uint32_t resid) const {

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
            return std::nullopt;
          }

          // @null is different than @empty.
          if (entry.value.dataType == Res_value::TYPE_NULL &&
              entry.value.data != Res_value::DATA_NULL_EMPTY) {
            return std::nullopt;
          }

          return AssetManager2::SelectedValue(entry.value.dataType, entry.value.data, entry.cookie,
                                              type_spec_flags, 0U /* resid */, {} /* config */);
        }
      }
    }
    break;
  } while (true);
  return std::nullopt;
}

base::expected<std::monostate, NullOrIOError> Theme::ResolveAttributeReference(
      AssetManager2::SelectedValue& value) const {
  if (value.type != Res_value::TYPE_ATTRIBUTE) {
    return asset_manager_->ResolveReference(value);
  }

  std::optional<AssetManager2::SelectedValue> result = GetAttribute(value.data);
  if (!result.has_value()) {
    return base::unexpected(std::nullopt);
  }

  auto resolve_result = asset_manager_->ResolveReference(*result, true /* cache_value */);
  if (resolve_result.has_value()) {
    result->flags |= value.flags;
    value = *result;
  }
  return resolve_result;
}

void Theme::Clear() {
  type_spec_flags_ = 0u;
  for (std::unique_ptr<Package>& package : packages_) {
    package.reset();
  }
}

base::expected<std::monostate, IOError> Theme::SetTo(const Theme& o) {
  if (this == &o) {
    return {};
  }

  type_spec_flags_ = o.type_spec_flags_;

  if (asset_manager_ == o.asset_manager_) {
    // The theme comes from the same asset manager so all theme data can be copied exactly
    for (size_t p = 0; p < packages_.size(); p++) {
      const Package *package = o.packages_[p].get();
      if (package == nullptr) {
        // The other theme doesn't have this package, clear ours.
        packages_[p].reset();
        continue;
      }

      if (packages_[p] == nullptr) {
        // The other theme has this package, but we don't. Make one.
        packages_[p].reset(new Package());
      }

      for (size_t t = 0; t < package->types.size(); t++) {
        const ThemeType *type = package->types[t].get();
        if (type == nullptr) {
          // The other theme doesn't have this type, clear ours.
          packages_[p]->types[t].reset();
          continue;
        }

        // Create a new type and update it to theirs.
        const size_t type_alloc_size = sizeof(ThemeType) + (type->entry_count * sizeof(ThemeEntry));
        void *copied_data = malloc(type_alloc_size);
        memcpy(copied_data, type, type_alloc_size);
        packages_[p]->types[t].reset(reinterpret_cast<ThemeType *>(copied_data));
      }
    }
  } else {
    std::map<ApkAssetsCookie, ApkAssetsCookie> src_to_dest_asset_cookies;
    typedef std::map<int, int> SourceToDestinationRuntimePackageMap;
    std::map<ApkAssetsCookie, SourceToDestinationRuntimePackageMap> src_asset_cookie_id_map;

    // Determine which ApkAssets are loaded in both theme AssetManagers.
    const auto src_assets = o.asset_manager_->GetApkAssets();
    for (size_t i = 0; i < src_assets.size(); i++) {
      const ApkAssets* src_asset = src_assets[i];

      const auto dest_assets = asset_manager_->GetApkAssets();
      for (size_t j = 0; j < dest_assets.size(); j++) {
        const ApkAssets* dest_asset = dest_assets[j];
        if (src_asset != dest_asset) {
          // ResourcesManager caches and reuses ApkAssets when the same apk must be present in
          // multiple AssetManagers. Two ApkAssets point to the same version of the same resources
          // if they are the same instance.
          continue;
        }

        // Map the package ids of the asset in the source AssetManager to the package ids of the
        // asset in th destination AssetManager.
        SourceToDestinationRuntimePackageMap package_map;
        for (const auto& loaded_package : src_asset->GetLoadedArsc()->GetPackages()) {
          const int src_package_id = o.asset_manager_->GetAssignedPackageId(loaded_package.get());
          const int dest_package_id = asset_manager_->GetAssignedPackageId(loaded_package.get());
          package_map[src_package_id] = dest_package_id;
        }

        src_to_dest_asset_cookies.insert(std::make_pair(i, j));
        src_asset_cookie_id_map.insert(std::make_pair(i, package_map));
        break;
      }
    }

    // Reset the data in the destination theme.
    for (size_t p = 0; p < packages_.size(); p++) {
      if (packages_[p] != nullptr) {
        packages_[p].reset();
      }
    }

    for (size_t p = 0; p < packages_.size(); p++) {
      const Package *package = o.packages_[p].get();
      if (package == nullptr) {
        continue;
      }

      for (size_t t = 0; t < package->types.size(); t++) {
        const ThemeType *type = package->types[t].get();
        if (type == nullptr) {
          continue;
        }

        for (size_t e = 0; e < type->entry_count; e++) {
          const ThemeEntry &entry = type->entries[e];
          if (entry.value.dataType == Res_value::TYPE_NULL &&
              entry.value.data != Res_value::DATA_NULL_EMPTY) {
            continue;
          }

          bool is_reference = (entry.value.dataType == Res_value::TYPE_ATTRIBUTE
                               || entry.value.dataType == Res_value::TYPE_REFERENCE
                               || entry.value.dataType == Res_value::TYPE_DYNAMIC_ATTRIBUTE
                               || entry.value.dataType == Res_value::TYPE_DYNAMIC_REFERENCE)
                              && entry.value.data != 0x0;

          // If the attribute value represents an attribute or reference, the package id of the
          // value needs to be rewritten to the package id of the value in the destination.
          uint32_t attribute_data = entry.value.data;
          if (is_reference) {
            // Determine the package id of the reference in the destination AssetManager.
            auto value_package_map = src_asset_cookie_id_map.find(entry.cookie);
            if (value_package_map == src_asset_cookie_id_map.end()) {
              continue;
            }

            auto value_dest_package = value_package_map->second.find(
                get_package_id(entry.value.data));
            if (value_dest_package == value_package_map->second.end()) {
              continue;
            }

            attribute_data = fix_package_id(entry.value.data, value_dest_package->second);
          }

          // Find the cookie of the value in the destination. If the source apk is not loaded in the
          // destination, only copy resources that do not reference resources in the source.
          ApkAssetsCookie data_dest_cookie;
          auto value_dest_cookie = src_to_dest_asset_cookies.find(entry.cookie);
          if (value_dest_cookie != src_to_dest_asset_cookies.end()) {
            data_dest_cookie = value_dest_cookie->second;
          } else {
            if (is_reference || entry.value.dataType == Res_value::TYPE_STRING) {
              continue;
            } else {
              data_dest_cookie = 0x0;
            }
          }

          // The package id of the attribute needs to be rewritten to the package id of the
          // attribute in the destination.
          int attribute_dest_package_id = p;
          if (attribute_dest_package_id != 0x01) {
            // Find the cookie of the attribute resource id in the source AssetManager
            base::expected<FindEntryResult, NullOrIOError> attribute_entry_result =
                o.asset_manager_->FindEntry(make_resid(p, t, e), 0 /* density_override */ ,
                                            true /* stop_at_first_match */,
                                            true /* ignore_configuration */);
            if (UNLIKELY(IsIOError(attribute_entry_result))) {
              return base::unexpected(GetIOError(attribute_entry_result.error()));
            }
            if (!attribute_entry_result.has_value()) {
              continue;
            }

            // Determine the package id of the attribute in the destination AssetManager.
            auto attribute_package_map = src_asset_cookie_id_map.find(
                attribute_entry_result->cookie);
            if (attribute_package_map == src_asset_cookie_id_map.end()) {
              continue;
            }
            auto attribute_dest_package = attribute_package_map->second.find(
                attribute_dest_package_id);
            if (attribute_dest_package == attribute_package_map->second.end()) {
              continue;
            }
            attribute_dest_package_id = attribute_dest_package->second;
          }

          // Lazily instantiate the destination package.
          std::unique_ptr<Package>& dest_package = packages_[attribute_dest_package_id];
          if (dest_package == nullptr) {
            dest_package.reset(new Package());
          }

          // Lazily instantiate and resize the destination type.
          util::unique_cptr<ThemeType>& dest_type = dest_package->types[t];
          if (dest_type == nullptr || dest_type->entry_count < type->entry_count) {
            const size_t type_alloc_size = sizeof(ThemeType)
                + (type->entry_count * sizeof(ThemeEntry));
            void* dest_data = malloc(type_alloc_size);
            memset(dest_data, 0, type->entry_count * sizeof(ThemeEntry));

            // Copy the existing destination type values if the type is resized.
            if (dest_type != nullptr) {
              memcpy(dest_data, type, sizeof(ThemeType)
                                      + (dest_type->entry_count * sizeof(ThemeEntry)));
            }

            dest_type.reset(reinterpret_cast<ThemeType *>(dest_data));
            dest_type->entry_count = type->entry_count;
          }

          dest_type->entries[e].cookie = data_dest_cookie;
          dest_type->entries[e].value.dataType = entry.value.dataType;
          dest_type->entries[e].value.data = attribute_data;
          dest_type->entries[e].type_spec_flags = entry.type_spec_flags;
        }
      }
    }
  }
  return {};
}

void Theme::Dump() const {
  base::ScopedLogSeverity _log(base::INFO);
  LOG(INFO) << base::StringPrintf("Theme(this=%p, AssetManager2=%p)", this, asset_manager_);

  for (int p = 0; p < packages_.size(); p++) {
    auto& package = packages_[p];
    if (package == nullptr) {
      continue;
    }

    for (int t = 0; t < package->types.size(); t++) {
      auto& type = package->types[t];
      if (type == nullptr) {
        continue;
      }

      for (int e = 0; e < type->entry_count; e++) {
        auto& entry = type->entries[e];
        if (entry.value.dataType == Res_value::TYPE_NULL &&
            entry.value.data != Res_value::DATA_NULL_EMPTY) {
          continue;
        }

        LOG(INFO) << base::StringPrintf("  entry(0x%08x)=(0x%08x) type=(0x%02x), cookie(%d)",
                                        make_resid(p, t, e), entry.value.data,
                                        entry.value.dataType, entry.cookie);
      }
    }
  }
}

}  // namespace android
