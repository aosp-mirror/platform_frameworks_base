/*
 * Copyright (C) 2021 The Android Open Source Project
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

#ifndef IDMAP2_INCLUDE_IDMAP2_RESOURCECONTAINER_H_
#define IDMAP2_INCLUDE_IDMAP2_RESOURCECONTAINER_H_

#include <memory>
#include <string>
#include <variant>
#include <vector>

#include "idmap2/Policies.h"
#include "idmap2/ResourceUtils.h"

namespace android::idmap2 {

struct ResourceContainer {
  WARN_UNUSED virtual Result<uint32_t> GetCrc() const = 0;
  WARN_UNUSED virtual const std::string& GetPath() const = 0;
  WARN_UNUSED virtual Result<std::string> GetResourceName(ResourceId id) const = 0;

  virtual ~ResourceContainer() = default;
};

struct TargetResourceContainer : public ResourceContainer {
  static Result<std::unique_ptr<TargetResourceContainer>> FromPath(std::string path);

  WARN_UNUSED virtual Result<bool> DefinesOverlayable() const = 0;
  WARN_UNUSED virtual Result<const android::OverlayableInfo*> GetOverlayableInfo(
      ResourceId id) const = 0;
  WARN_UNUSED virtual Result<ResourceId> GetResourceId(const std::string& name) const = 0;

  ~TargetResourceContainer() override = default;
};

struct OverlayManifestInfo {
  std::string package_name;     // NOLINT(misc-non-private-member-variables-in-classes)
  std::string name;             // NOLINT(misc-non-private-member-variables-in-classes)
  std::string target_package;   // NOLINT(misc-non-private-member-variables-in-classes)
  std::string target_name;      // NOLINT(misc-non-private-member-variables-in-classes)
  ResourceId resource_mapping;  // NOLINT(misc-non-private-member-variables-in-classes)
};

struct OverlayData {
  struct ResourceIdValue {
    // The overlay resource id.
    ResourceId overlay_id;

    // Whether or not references to the overlay resource id should be rewritten to its corresponding
    // target id during resource resolution.
    bool rewrite_id;
  };

  struct Value {
    std::string resource_name;
    std::variant<ResourceIdValue, TargetValue> value;
  };

  struct InlineStringPoolData {
    // The binary data of the android::ResStringPool string pool.
    std::unique_ptr<uint8_t[]> data;

    // The length of the binary data.
    uint32_t data_length;

    // The offset added to TargetValue#data_value (the index of the string in the inline string
    // pool) in order to prevent the indices of the overlay resource table string pool from
    // colliding with the inline string pool indices.
    uint32_t string_pool_offset;
  };

  // The overlay's mapping of target resource name to overlaid value. Use a vector to enforce that
  // the overlay pairs are inserted into the ResourceMapping in the specified ordered.
  std::vector<Value> pairs;

  // If the overlay maps a target resource to a string literal (not a string resource), then the
  // this field contains information about the string pool in which the string literal resides so it
  // can be inlined into an idmap.
  std::optional<InlineStringPoolData> string_pool_data;
};

struct OverlayResourceContainer : public ResourceContainer {
  static Result<std::unique_ptr<OverlayResourceContainer>> FromPath(std::string path);

  WARN_UNUSED virtual Result<OverlayManifestInfo> FindOverlayInfo(
      const std::string& name) const = 0;
  WARN_UNUSED virtual Result<OverlayData> GetOverlayData(const OverlayManifestInfo& info) const = 0;

  ~OverlayResourceContainer() override = default;
};

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_RESOURCECONTAINER_H_
