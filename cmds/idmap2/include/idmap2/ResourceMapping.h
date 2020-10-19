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

#ifndef IDMAP2_INCLUDE_IDMAP2_RESOURCEMAPPING_H_
#define IDMAP2_INCLUDE_IDMAP2_RESOURCEMAPPING_H_

#include <map>
#include <memory>
#include <utility>

#include "androidfw/ApkAssets.h"
#include "idmap2/LogInfo.h"
#include "idmap2/Policies.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"
#include "idmap2/XmlParser.h"

using android::idmap2::utils::OverlayManifestInfo;

using PolicyBitmask = android::ResTable_overlayable_policy_header::PolicyBitmask;

namespace android::idmap2 {

struct TargetValue {
  typedef uint8_t DataType;
  typedef uint32_t DataValue;
  DataType data_type;
  DataValue data_value;
};

using TargetResourceMap = std::map<ResourceId, std::variant<ResourceId, TargetValue>>;
using OverlayResourceMap = std::map<ResourceId, ResourceId>;

class ResourceMapping {
 public:
  // Creates a ResourceMapping using the target and overlay APKs. Setting enforce_overlayable to
  // `false` disables all overlayable and policy enforcement: this is intended for backwards
  // compatibility pre-Q and unit tests.
  static Result<ResourceMapping> FromApkAssets(const ApkAssets& target_apk_assets,
                                               const ApkAssets& overlay_apk_assets,
                                               const OverlayManifestInfo& overlay_info,
                                               const PolicyBitmask& fulfilled_policies,
                                               bool enforce_overlayable, LogInfo& log_info);

  // Retrieves the mapping of target resource id to overlay value.
  inline const TargetResourceMap& GetTargetToOverlayMap() const {
    return target_map_;
  }

  // Retrieves the mapping of overlay resource id to target resource id. This allows a reference to
  // an overlay resource to appear as a reference to its corresponding target resource at runtime.
  OverlayResourceMap GetOverlayToTargetMap() const;

  // Retrieves the build-time package id of the target package.
  inline uint32_t GetTargetPackageId() const {
    return target_package_id_;
  }

  // Retrieves the build-time package id of the overlay package.
  inline uint32_t GetOverlayPackageId() const {
    return overlay_package_id_;
  }

  // Retrieves the offset that was added to the index of inline string overlay values so the indices
  // do not collide with the indices of the overlay resource table string pool.
  inline uint32_t GetStringPoolOffset() const {
    return string_pool_offset_;
  }

  // Retrieves the raw string pool data from the xml referenced in android:resourcesMap.
  inline const StringPiece GetStringPoolData() const {
    return StringPiece(reinterpret_cast<const char*>(string_pool_data_.get()),
                       string_pool_data_length_);
  }

 private:
  ResourceMapping() = default;

  // Maps a target resource id to an overlay resource id.
  // If rewrite_overlay_reference is `true` then references to the overlay
  // resource should appear as a reference to its corresponding target resource at runtime.
  Result<Unit> AddMapping(ResourceId target_resource, ResourceId overlay_resource,
                          bool rewrite_overlay_reference);

  // Maps a target resource id to a data type and value combination.
  // The `data_type` is the runtime format of the data value (see Res_value::dataType).
  Result<Unit> AddMapping(ResourceId target_resource, TargetValue::DataType data_type,
                          TargetValue::DataValue data_value);

  // Removes the overlay value mapping for the target resource.
  void RemoveMapping(ResourceId target_resource);

  // Parses the mapping of target resources to overlay resources to generate a ResourceMapping.
  static Result<ResourceMapping> CreateResourceMapping(const AssetManager2* target_am,
                                                       const LoadedPackage* target_package,
                                                       const LoadedPackage* overlay_package,
                                                       size_t string_pool_offset,
                                                       const XmlParser& overlay_parser,
                                                       LogInfo& log_info);

  // Generates a ResourceMapping that maps target resources to overlay resources by name. To overlay
  // a target resource, a resource must exist in the overlay with the same type and entry name as
  // the target resource.
  static Result<ResourceMapping> CreateResourceMappingLegacy(const AssetManager2* target_am,
                                                             const AssetManager2* overlay_am,
                                                             const LoadedPackage* target_package,
                                                             const LoadedPackage* overlay_package);

  // Removes resources that do not pass policy or overlayable checks of the target package.
  void FilterOverlayableResources(const AssetManager2* target_am,
                                  const LoadedPackage* target_package,
                                  const LoadedPackage* overlay_package,
                                  const OverlayManifestInfo& overlay_info,
                                  const PolicyBitmask& fulfilled_policies, LogInfo& log_info);

  TargetResourceMap target_map_;
  std::multimap<ResourceId, ResourceId> overlay_map_;

  uint32_t target_package_id_ = 0;
  uint32_t overlay_package_id_ = 0;
  uint32_t string_pool_offset_ = 0;
  uint32_t string_pool_data_length_ = 0;
  std::unique_ptr<uint8_t[]> string_pool_data_ = nullptr;
};

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_RESOURCEMAPPING_H_
