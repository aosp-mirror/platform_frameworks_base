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
using PolicyBitmask = android::ResTable_overlayable_policy_header::PolicyBitmask;
using PolicyFlags = android::ResTable_overlayable_policy_header::PolicyFlags;

namespace android::idmap2 {

namespace {
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

Result<Unit> CheckOverlayable(const TargetResourceContainer& target,
                              const OverlayManifestInfo& overlay_info,
                              const PolicyBitmask& fulfilled_policies,
                              const ResourceId& target_resource) {
  constexpr const PolicyBitmask kDefaultPolicies =
      PolicyFlags::ODM_PARTITION | PolicyFlags::OEM_PARTITION | PolicyFlags::SYSTEM_PARTITION |
      PolicyFlags::VENDOR_PARTITION | PolicyFlags::PRODUCT_PARTITION | PolicyFlags::SIGNATURE |
      PolicyFlags::CONFIG_SIGNATURE;

  // If the resource does not have an overlayable definition, allow the resource to be overlaid if
  // the overlay is preinstalled, signed with the same signature as the target or signed with the
  // same signature as reference package defined in SystemConfig under 'overlay-config-signature'
  // tag.
  const Result<bool> defines_overlayable = target.DefinesOverlayable();
  if (!defines_overlayable) {
    return Error(defines_overlayable.GetError(), "unable to retrieve overlayable info");
  }

  if (!*defines_overlayable) {
    return (kDefaultPolicies & fulfilled_policies) != 0
               ? Result<Unit>({})
               : Error(
                     "overlay must be preinstalled, signed with the same signature as the target,"
                     " or signed with the same signature as the package referenced through"
                     " <overlay-config-signature>.");
  }

  const auto overlayable_info = target.GetOverlayableInfo(target_resource);
  if (!overlayable_info) {
    return overlayable_info.GetError();
  }

  if (*overlayable_info == nullptr) {
    // Do not allow non-overlayable resources to be overlaid.
    return Error("target resource has no overlayable declaration");
  }

  if (overlay_info.target_name != (*overlayable_info)->name) {
    // If the overlay supplies a target overlayable name, the resource must belong to the
    // overlayable defined with the specified name to be overlaid.
    return Error(R"(<overlay> android:targetName "%s" does not match overlayable name "%s")",
                 overlay_info.target_name.c_str(), (*overlayable_info)->name.c_str());
  }

  // Enforce policy restrictions if the resource is declared as overlayable.
  if (((*overlayable_info)->policy_flags & fulfilled_policies) == 0) {
    return Error(R"(overlay with policies "%s" does not fulfill any overlayable policies "%s")",
                 ConcatPolicies(BitmaskToPolicies(fulfilled_policies)).c_str(),
                 ConcatPolicies(BitmaskToPolicies((*overlayable_info)->policy_flags)).c_str());
  }

  return Result<Unit>({});
}

std::string GetDebugResourceName(const ResourceContainer& container, ResourceId resid) {
  auto name = container.GetResourceName(resid);
  if (name) {
    return *name;
  }
  return StringPrintf("0x%08x", resid);
}
}  // namespace

Result<ResourceMapping> ResourceMapping::FromContainers(const TargetResourceContainer& target,
                                                        const OverlayResourceContainer& overlay,
                                                        const OverlayManifestInfo& overlay_info,
                                                        const PolicyBitmask& fulfilled_policies,
                                                        bool enforce_overlayable,
                                                        LogInfo& log_info) {
  auto overlay_data = overlay.GetOverlayData(overlay_info);
  if (!overlay_data) {
    return overlay_data.GetError();
  }

  ResourceMapping mapping;
  for (const auto& overlay_pair : overlay_data->pairs) {
    const auto target_resid = target.GetResourceId(overlay_pair.resource_name);
    if (!target_resid) {
      log_info.Warning(LogMessage() << target_resid.GetErrorMessage());
      continue;
    }

    if (enforce_overlayable) {
      // Filter out resources the overlay is not allowed to override.
      auto overlayable = CheckOverlayable(target, overlay_info, fulfilled_policies, *target_resid);
      if (!overlayable) {
        log_info.Warning(LogMessage() << "overlay '" << overlay.GetPath()
                                      << "' is not allowed to overlay resource '"
                                      << GetDebugResourceName(target, *target_resid)
                                      << "' in target: " << overlayable.GetErrorMessage());
        continue;
      }
    }

    if (auto result = mapping.AddMapping(*target_resid, overlay_pair.value); !result) {
      return Error(result.GetError(), "failed to add mapping for '%s'",
                   GetDebugResourceName(target, *target_resid).c_str());
    }
  }

  auto& string_pool_data = overlay_data->string_pool_data;
  if (string_pool_data.has_value()) {
    mapping.string_pool_offset_ = string_pool_data->string_pool_offset;
    mapping.string_pool_data_ = std::move(string_pool_data->data);
    mapping.string_pool_data_length_ = string_pool_data->data_length;
  }

  return std::move(mapping);
}

Result<Unit> ResourceMapping::AddMapping(
    ResourceId target_resource,
    const std::variant<OverlayData::ResourceIdValue, TargetValue>& value) {
  if (target_map_.find(target_resource) != target_map_.end()) {
    return Error(R"(target resource id "0x%08x" mapped to multiple values)", target_resource);
  }

  // TODO(141485591): Ensure that the overlay type is compatible with the target type. If the
  // runtime types are not compatible, it could cause runtime crashes when the resource is resolved.

  if (auto overlay_resource = std::get_if<OverlayData::ResourceIdValue>(&value)) {
    target_map_.insert(std::make_pair(target_resource, overlay_resource->overlay_id));
    if (overlay_resource->rewrite_id) {
      // An overlay resource can override multiple target resources at once. Rewrite the overlay
      // resource as the first target resource it overrides.
      overlay_map_.insert(std::make_pair(overlay_resource->overlay_id, target_resource));
    }
  } else {
    auto overlay_value = std::get<TargetValue>(value);
    target_map_.insert(std::make_pair(target_resource, overlay_value));
  }

  return Unit{};
}

}  // namespace android::idmap2
