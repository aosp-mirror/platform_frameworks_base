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

#include <androidfw/ApkAssets.h>

#include <map>
#include <memory>
#include <utility>

#include "idmap2/FabricatedOverlay.h"
#include "idmap2/LogInfo.h"
#include "idmap2/Policies.h"
#include "idmap2/ResourceUtils.h"
#include "idmap2/Result.h"
#include "idmap2/XmlParser.h"

using PolicyBitmask = android::ResTable_overlayable_policy_header::PolicyBitmask;

namespace android::idmap2 {
using TargetResourceMap = std::map<ResourceId, std::variant<ResourceId, TargetValue>>;
using OverlayResourceMap = std::map<ResourceId, ResourceId>;

class ResourceMapping {
 public:
  // Creates a ResourceMapping using the target and overlay APKs. Setting enforce_overlayable to
  // `false` disables all overlayable and policy enforcement: this is intended for backwards
  // compatibility pre-Q and unit tests.
  static Result<ResourceMapping> FromContainers(const TargetResourceContainer& target,
                                                const OverlayResourceContainer& overlay,
                                                const OverlayManifestInfo& overlay_info,
                                                const PolicyBitmask& fulfilled_policies,
                                                bool enforce_overlayable, LogInfo& log_info);

  // Retrieves the mapping of target resource id to overlay value.
  WARN_UNUSED const TargetResourceMap& GetTargetToOverlayMap() const;

  // Retrieves the mapping of overlay resource id to target resource id. This allows a reference to
  // an overlay resource to appear as a reference to its corresponding target resource at runtime.
  WARN_UNUSED const OverlayResourceMap& GetOverlayToTargetMap() const;

  // Retrieves the offset that was added to the index of inline string overlay values so the indices
  // do not collide with the indices of the overlay resource table string pool.
  WARN_UNUSED uint32_t GetStringPoolOffset() const;

  // Retrieves the raw string pool data from the xml referenced in android:resourcesMap.
  WARN_UNUSED StringPiece GetStringPoolData() const;

 private:
  ResourceMapping() = default;

  // Maps a target resource id to an overlay resource id or a android::Res_value value.
  //
  // If `allow_rewriting_` is true, then the overlay-to-target map will be populated if the target
  // resource id is mapped to an overlay resource id.
  Result<Unit> AddMapping(ResourceId target_resource,
                          const std::variant<OverlayData::ResourceIdValue, TargetValue>& value);

  TargetResourceMap target_map_;
  OverlayResourceMap overlay_map_;
  uint32_t string_pool_offset_ = 0;
  uint32_t string_pool_data_length_ = 0;
  std::unique_ptr<uint8_t[]> string_pool_data_ = nullptr;
};

inline const TargetResourceMap& ResourceMapping::GetTargetToOverlayMap() const {
  return target_map_;
}

inline const OverlayResourceMap& ResourceMapping::GetOverlayToTargetMap() const {
  return overlay_map_;
}

inline uint32_t ResourceMapping::GetStringPoolOffset() const {
  return string_pool_offset_;
}

inline StringPiece ResourceMapping::GetStringPoolData() const {
  return StringPiece(reinterpret_cast<const char*>(string_pool_data_.get()),
                     string_pool_data_length_);
}

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_RESOURCEMAPPING_H_
