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

#ifndef IDMAP2_INCLUDE_IDMAP2_POLICIES_H_
#define IDMAP2_INCLUDE_IDMAP2_POLICIES_H_

#include <array>
#include <string>
#include <vector>

#include "android-base/stringprintf.h"
#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"

using android::base::StringPrintf;

using PolicyBitmask = android::ResTable_overlayable_policy_header::PolicyBitmask;
using PolicyFlags = android::ResTable_overlayable_policy_header::PolicyFlags;

namespace android::idmap2::policy {

constexpr const char* kPolicyActor = "actor";
constexpr const char* kPolicyOdm = "odm";
constexpr const char* kPolicyOem = "oem";
constexpr const char* kPolicyProduct = "product";
constexpr const char* kPolicyPublic = "public";
constexpr const char* kPolicySignature = "signature";
constexpr const char* kPolicySystem = "system";
constexpr const char* kPolicyVendor = "vendor";

inline static const std::array<std::pair<StringPiece, PolicyFlags>, 8> kPolicyStringToFlag = {
    std::pair{kPolicyActor, PolicyFlags::ACTOR_SIGNATURE},
    {kPolicyOdm, PolicyFlags::ODM_PARTITION},
    {kPolicyOem, PolicyFlags::OEM_PARTITION},
    {kPolicyProduct, PolicyFlags::PRODUCT_PARTITION},
    {kPolicyPublic, PolicyFlags::PUBLIC},
    {kPolicySignature, PolicyFlags::SIGNATURE},
    {kPolicySystem, PolicyFlags::SYSTEM_PARTITION},
    {kPolicyVendor, PolicyFlags::VENDOR_PARTITION},
};

inline static std::string PoliciesToDebugString(PolicyBitmask policies) {
  std::string str;
  uint32_t remaining = policies;
  for (auto const& policy : kPolicyStringToFlag) {
    if ((policies & policy.second) != policy.second) {
      continue;
    }
    if (!str.empty()) {
      str.append("|");
    }
    str.append(policy.first.data());
    remaining &= ~policy.second;
  }
  if (remaining != 0) {
    if (!str.empty()) {
      str.append("|");
    }
    str.append(StringPrintf("0x%08x", remaining));
  }
  return !str.empty() ? str : "none";
}

}  // namespace android::idmap2::policy

#endif  // IDMAP2_INCLUDE_IDMAP2_POLICIES_H_
