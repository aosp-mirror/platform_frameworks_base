/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "idmap2/ResourceMapping.h"

#include <map>
#include <memory>
#include <set>
#include <string>
#include <utility>
#include <vector>

#include "android-base/stringprintf.h"
#include "androidfw/ResourceTypes.h"
#include "idmap2/PolicyUtils.h"
#include "idmap2/ResourceUtils.h"

using android::base::StringPrintf;
using android::idmap2::utils::BitmaskToPolicies;
using android::idmap2::utils::IsReference;
using android::idmap2::utils::ResToTypeEntryName;
using PolicyBitmask = android::ResTable_overlayable_policy_header::PolicyBitmask;
using PolicyFlags = android::ResTable_overlayable_policy_header::PolicyFlags;

namespace android::idmap2 {

namespace {

#define REWRITE_PACKAGE(resid, package_id) \
  (((resid)&0x00ffffffU) | (((uint32_t)(package_id)) << 24U))
#define EXTRACT_PACKAGE(resid) ((0xff000000 & (resid)) >> 24)

std::string ConcatPolicies(const std::vector<std::string>& policies) {
  std::string message;
  for (const std::string& policy : policies) {
    if (!message.empty()) {
      message.append("|");
    }
    message.append(policy);
  }

  return message;
}

Result<Unit> CheckOverlayable(const LoadedPackage& target_package,
                              const OverlayManifestInfo& overlay_info,
                              const PolicyBitmask& fulfilled_policies,
                              const ResourceId& target_resource) {
  static constexpr const PolicyBitmask sDefaultPolicies =
      PolicyFlags::ODM_PARTITION | PolicyFlags::OEM_PARTITION | PolicyFlags::SYSTEM_PARTITION |
      PolicyFlags::VENDOR_PARTITION | PolicyFlags::PRODUCT_PARTITION | PolicyFlags::SIGNATURE;

  // If the resource does not have an overlayable definition, allow the resource to be overlaid if
  // the overlay is preinstalled or signed with the same signature as the target.
  if (!target_package.DefinesOverlayable()) {
    return (sDefaultPolicies & fulfilled_policies) != 0
               ? Result<Unit>({})
               : Error(
                     "overlay must be preinstalled or signed with the same signature as the "
                     "target");
  }

  const OverlayableInfo* overlayable_info = target_package.GetOverlayableInfo(target_resource);
  if (overlayable_info == nullptr) {
    // Do not allow non-overlayable resources to be overlaid.
    return Error("target resource has no overlayable declaration");
  }

  if (overlay_info.target_name != overlayable_info->name) {
    // If the overlay supplies a target overlayable name, the resource must belong to the
    // overlayable defined with the specified name to be overlaid.
    return Error(R"(<overlay> android:targetName "%s" does not match overlayable name "%s")",
                 overlay_info.target_name.c_str(), overlayable_info->name.c_str());
  }

  // Enforce policy restrictions if the resource is declared as overlayable.
  if ((overlayable_info->policy_flags & fulfilled_policies) == 0) {
    return Error(R"(overlay with policies "%s" does not fulfill any overlayable policies "%s")",
                 ConcatPolicies(BitmaskToPolicies(fulfilled_policies)).c_str(),
                 ConcatPolicies(BitmaskToPolicies(overlayable_info->policy_flags)).c_str());
  }

  return Result<Unit>({});
}

// TODO(martenkongstad): scan for package name instead of assuming package at index 0
//
// idmap version 0x01 naively assumes that the package to use is always the first ResTable_package
// in the resources.arsc blob. In most cases, there is only a single ResTable_package anyway, so
// this assumption tends to work out. That said, the correct thing to do is to scan
// resources.arsc for a package with a given name as read from the package manifest instead of
// relying on a hard-coded index. This however requires storing the package name in the idmap
// header, which in turn requires incrementing the idmap version. Because the initial version of
// idmap2 is compatible with idmap, this will have to wait for now.
const LoadedPackage* GetPackageAtIndex0(const LoadedArsc& loaded_arsc) {
  const std::vector<std::unique_ptr<const LoadedPackage>>& packages = loaded_arsc.GetPackages();
  if (packages.empty()) {
    return nullptr;
  }
  int id = packages[0]->GetPackageId();
  return loaded_arsc.GetPackageById(id);
}

Result<std::unique_ptr<Asset>> OpenNonAssetFromResource(const ResourceId& resource_id,
                                                        const AssetManager2& asset_manager) {
  Res_value value{};
  ResTable_config selected_config{};
  uint32_t flags;
  auto cookie =
      asset_manager.GetResource(resource_id, /* may_be_bag */ false,
                                /* density_override */ 0U, &value, &selected_config, &flags);
  if (cookie == kInvalidCookie) {
    return Error("failed to find resource for id 0x%08x", resource_id);
  }

  if (value.dataType != Res_value::TYPE_STRING) {
    return Error("resource for is 0x%08x is not a file", resource_id);
  }

  auto string_pool = asset_manager.GetStringPoolForCookie(cookie);
  size_t len;
  auto file_path16 = string_pool->stringAt(value.data, &len);
  if (file_path16 == nullptr) {
    return Error("failed to find string for index %d", value.data);
  }

  // Load the overlay resource mappings from the file specified using android:resourcesMap.
  auto file_path = String8(String16(file_path16));
  auto asset = asset_manager.OpenNonAsset(file_path.c_str(), Asset::AccessMode::ACCESS_BUFFER);
  if (asset == nullptr) {
    return Error("file \"%s\" not found", file_path.c_str());
  }

  return asset;
}

}  // namespace

Result<ResourceMapping> ResourceMapping::CreateResourceMapping(const AssetManager2* target_am,
                                                               const LoadedPackage* target_package,
                                                               const LoadedPackage* overlay_package,
                                                               size_t string_pool_offset,
                                                               const XmlParser& overlay_parser,
                                                               LogInfo& log_info) {
  ResourceMapping resource_mapping;
  auto root_it = overlay_parser.tree_iterator();
  if (root_it->event() != XmlParser::Event::START_TAG || root_it->name() != "overlay") {
    return Error("root element is not <overlay> tag");
  }

  const uint8_t target_package_id = target_package->GetPackageId();
  const uint8_t overlay_package_id = overlay_package->GetPackageId();
  auto overlay_it_end = root_it.end();
  for (auto overlay_it = root_it.begin(); overlay_it != overlay_it_end; ++overlay_it) {
    if (overlay_it->event() == XmlParser::Event::BAD_DOCUMENT) {
      return Error("failed to parse overlay xml document");
    }

    if (overlay_it->event() != XmlParser::Event::START_TAG) {
      continue;
    }

    if (overlay_it->name() != "item") {
      return Error("unexpected tag <%s> in <overlay>", overlay_it->name().c_str());
    }

    Result<std::string> target_resource = overlay_it->GetAttributeStringValue("target");
    if (!target_resource) {
      return Error(R"(<item> tag missing expected attribute "target")");
    }

    Result<android::Res_value> overlay_resource = overlay_it->GetAttributeValue("value");
    if (!overlay_resource) {
      return Error(R"(<item> tag missing expected attribute "value")");
    }

    ResourceId target_id =
        target_am->GetResourceId(*target_resource, "", target_package->GetPackageName());
    if (target_id == 0U) {
      log_info.Warning(LogMessage() << "failed to find resource \"" << *target_resource
                                    << "\" in target resources");
      continue;
    }

    // Retrieve the compile-time resource id of the target resource.
    target_id = REWRITE_PACKAGE(target_id, target_package_id);

    if (overlay_resource->dataType == Res_value::TYPE_STRING) {
      overlay_resource->data += string_pool_offset;
    }

    // Only rewrite resources defined within the overlay package to their corresponding target
    // resource ids at runtime.
    bool rewrite_overlay_reference =
        IsReference(overlay_resource->dataType)
            ? overlay_package_id == EXTRACT_PACKAGE(overlay_resource->data)
            : false;

    if (rewrite_overlay_reference) {
      overlay_resource->dataType = Res_value::TYPE_DYNAMIC_REFERENCE;
    }

    resource_mapping.AddMapping(target_id, overlay_resource->dataType, overlay_resource->data,
                                rewrite_overlay_reference);
  }

  return resource_mapping;
}

Result<ResourceMapping> ResourceMapping::CreateResourceMappingLegacy(
    const AssetManager2* target_am, const AssetManager2* overlay_am,
    const LoadedPackage* target_package, const LoadedPackage* overlay_package) {
  ResourceMapping resource_mapping;
  const uint8_t target_package_id = target_package->GetPackageId();
  const auto end = overlay_package->end();
  for (auto iter = overlay_package->begin(); iter != end; ++iter) {
    const ResourceId overlay_resid = *iter;
    Result<std::string> name = utils::ResToTypeEntryName(*overlay_am, overlay_resid);
    if (!name) {
      continue;
    }

    // Find the resource with the same type and entry name within the target package.
    const std::string full_name =
        base::StringPrintf("%s:%s", target_package->GetPackageName().c_str(), name->c_str());
    ResourceId target_resource = target_am->GetResourceId(full_name);
    if (target_resource == 0U) {
      continue;
    }

    // Retrieve the compile-time resource id of the target resource.
    target_resource = REWRITE_PACKAGE(target_resource, target_package_id);

    resource_mapping.AddMapping(target_resource, Res_value::TYPE_REFERENCE, overlay_resid,
                                /* rewrite_overlay_reference */ false);
  }

  return resource_mapping;
}

void ResourceMapping::FilterOverlayableResources(const AssetManager2* target_am,
                                                 const LoadedPackage* target_package,
                                                 const LoadedPackage* overlay_package,
                                                 const OverlayManifestInfo& overlay_info,
                                                 const PolicyBitmask& fulfilled_policies,
                                                 LogInfo& log_info) {
  std::set<ResourceId> remove_ids;
  for (const auto& target_map : target_map_) {
    const ResourceId target_resid = target_map.first;
    Result<Unit> success =
        CheckOverlayable(*target_package, overlay_info, fulfilled_policies, target_resid);
    if (success) {
      continue;
    }

    // Attempting to overlay a resource that is not allowed to be overlaid is treated as a
    // warning.
    Result<std::string> name = utils::ResToTypeEntryName(*target_am, target_resid);
    if (!name) {
      name = StringPrintf("0x%08x", target_resid);
    }

    log_info.Warning(LogMessage() << "overlay \"" << overlay_package->GetPackageName()
                                  << "\" is not allowed to overlay resource \"" << *name
                                  << "\" in target: " << success.GetErrorMessage());

    remove_ids.insert(target_resid);
  }

  for (const ResourceId target_resid : remove_ids) {
    RemoveMapping(target_resid);
  }
}

Result<ResourceMapping> ResourceMapping::FromApkAssets(const ApkAssets& target_apk_assets,
                                                       const ApkAssets& overlay_apk_assets,
                                                       const OverlayManifestInfo& overlay_info,
                                                       const PolicyBitmask& fulfilled_policies,
                                                       bool enforce_overlayable,
                                                       LogInfo& log_info) {
  AssetManager2 target_asset_manager;
  if (!target_asset_manager.SetApkAssets({&target_apk_assets}, true /* invalidate_caches */,
                                         false /* filter_incompatible_configs*/)) {
    return Error("failed to create target asset manager");
  }

  AssetManager2 overlay_asset_manager;
  if (!overlay_asset_manager.SetApkAssets({&overlay_apk_assets}, true /* invalidate_caches */,
                                          false /* filter_incompatible_configs */)) {
    return Error("failed to create overlay asset manager");
  }

  const LoadedArsc* target_arsc = target_apk_assets.GetLoadedArsc();
  if (target_arsc == nullptr) {
    return Error("failed to load target resources.arsc");
  }

  const LoadedArsc* overlay_arsc = overlay_apk_assets.GetLoadedArsc();
  if (overlay_arsc == nullptr) {
    return Error("failed to load overlay resources.arsc");
  }

  const LoadedPackage* target_pkg = GetPackageAtIndex0(*target_arsc);
  if (target_pkg == nullptr) {
    return Error("failed to load target package from resources.arsc");
  }

  const LoadedPackage* overlay_pkg = GetPackageAtIndex0(*overlay_arsc);
  if (overlay_pkg == nullptr) {
    return Error("failed to load overlay package from resources.arsc");
  }

  size_t string_pool_data_length = 0U;
  size_t string_pool_offset = 0U;
  std::unique_ptr<uint8_t[]> string_pool_data;
  Result<ResourceMapping> resource_mapping = {{}};
  if (overlay_info.resource_mapping != 0U) {
    // Use the dynamic reference table to find the assigned resource id of the map xml.
    const auto& ref_table = overlay_asset_manager.GetDynamicRefTableForCookie(0);
    uint32_t resource_mapping_id = overlay_info.resource_mapping;
    ref_table->lookupResourceId(&resource_mapping_id);

    // Load the overlay resource mappings from the file specified using android:resourcesMap.
    auto asset = OpenNonAssetFromResource(resource_mapping_id, overlay_asset_manager);
    if (!asset) {
      return Error("failed opening xml for android:resourcesMap: %s",
                   asset.GetErrorMessage().c_str());
    }

    auto parser =
        XmlParser::Create((*asset)->getBuffer(true /* wordAligned*/), (*asset)->getLength());
    if (!parser) {
      return Error("failed opening ResXMLTree");
    }

    // Copy the xml string pool data before the parse goes out of scope.
    auto& string_pool = (*parser)->get_strings();
    string_pool_data_length = string_pool.bytes();
    string_pool_data.reset(new uint8_t[string_pool_data_length]);
    memcpy(string_pool_data.get(), string_pool.data(), string_pool_data_length);

    // Offset string indices by the size of the overlay resource table string pool.
    string_pool_offset = overlay_arsc->GetStringPool()->size();

    resource_mapping = CreateResourceMapping(&target_asset_manager, target_pkg, overlay_pkg,
                                             string_pool_offset, *(*parser), log_info);
  } else {
    // If no file is specified using android:resourcesMap, it is assumed that the overlay only
    // defines resources intended to override target resources of the same type and name.
    resource_mapping = CreateResourceMappingLegacy(&target_asset_manager, &overlay_asset_manager,
                                                   target_pkg, overlay_pkg);
  }

  if (!resource_mapping) {
    return resource_mapping.GetError();
  }

  if (enforce_overlayable) {
    // Filter out resources the overlay is not allowed to override.
    (*resource_mapping)
        .FilterOverlayableResources(&target_asset_manager, target_pkg, overlay_pkg, overlay_info,
                                    fulfilled_policies, log_info);
  }

  resource_mapping->target_package_id_ = target_pkg->GetPackageId();
  resource_mapping->overlay_package_id_ = overlay_pkg->GetPackageId();
  resource_mapping->string_pool_offset_ = string_pool_offset;
  resource_mapping->string_pool_data_ = std::move(string_pool_data);
  resource_mapping->string_pool_data_length_ = string_pool_data_length;
  return std::move(*resource_mapping);
}

OverlayResourceMap ResourceMapping::GetOverlayToTargetMap() const {
  // An overlay resource can override multiple target resources at once. Rewrite the overlay
  // resource as the first target resource it overrides.
  OverlayResourceMap map;
  for (const auto& mappings : overlay_map_) {
    map.insert(std::make_pair(mappings.first, mappings.second));
  }
  return map;
}

Result<Unit> ResourceMapping::AddMapping(ResourceId target_resource,
                                         TargetValue::DataType data_type,
                                         TargetValue::DataValue data_value,
                                         bool rewrite_overlay_reference) {
  if (target_map_.find(target_resource) != target_map_.end()) {
    return Error(R"(target resource id "0x%08x" mapped to multiple values)", target_resource);
  }

  // TODO(141485591): Ensure that the overlay type is compatible with the target type. If the
  // runtime types are not compatible, it could cause runtime crashes when the resource is resolved.

  target_map_.insert(std::make_pair(target_resource, TargetValue{data_type, data_value}));

  if (rewrite_overlay_reference && IsReference(data_type)) {
    overlay_map_.insert(std::make_pair(data_value, target_resource));
  }

  return Result<Unit>({});
}

void ResourceMapping::RemoveMapping(ResourceId target_resource) {
  auto target_iter = target_map_.find(target_resource);
  if (target_iter == target_map_.end()) {
    return;
  }

  const TargetValue value = target_iter->second;
  target_map_.erase(target_iter);

  if (!IsReference(value.data_type)) {
    return;
  }

  auto overlay_iter = overlay_map_.equal_range(value.data_value);
  for (auto i = overlay_iter.first; i != overlay_iter.second; ++i) {
    if (i->second == target_resource) {
      overlay_map_.erase(i);
      return;
    }
  }
}

}  // namespace android::idmap2
