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

#include "idmap2/Policies.h"

#include <iterator>
#include <string>
#include <unordered_map>
#include <vector>

#include "androidfw/ResourceTypes.h"
#include "idmap2/Idmap.h"
#include "idmap2/Result.h"

namespace android::idmap2 {

Result<PolicyBitmask> PoliciesToBitmask(const std::vector<std::string>& policies) {
  static const std::unordered_map<android::StringPiece, PolicyFlags> kStringToFlag = {
      {kPolicyOdm, PolicyFlags::POLICY_ODM_PARTITION},
      {kPolicyOem, PolicyFlags::POLICY_OEM_PARTITION},
      {kPolicyPublic, PolicyFlags::POLICY_PUBLIC},
      {kPolicyProduct, PolicyFlags::POLICY_PRODUCT_PARTITION},
      {kPolicySignature, PolicyFlags::POLICY_SIGNATURE},
      {kPolicySystem, PolicyFlags::POLICY_SYSTEM_PARTITION},
      {kPolicyVendor, PolicyFlags::POLICY_VENDOR_PARTITION},
  };

  PolicyBitmask bitmask = 0;
  for (const std::string& policy : policies) {
    const auto iter = kStringToFlag.find(policy);
    if (iter != kStringToFlag.end()) {
      bitmask |= iter->second;
    } else {
      return Error("unknown policy \"%s\"", policy.c_str());
    }
  }

  return Result<PolicyBitmask>(bitmask);
}

std::vector<std::string> BitmaskToPolicies(const PolicyBitmask& bitmask) {
  std::vector<std::string> policies;

  if ((bitmask & PolicyFlags::POLICY_ODM_PARTITION) != 0) {
    policies.emplace_back(kPolicyOdm);
  }

  if ((bitmask & PolicyFlags::POLICY_OEM_PARTITION) != 0) {
    policies.emplace_back(kPolicyOem);
  }

  if ((bitmask & PolicyFlags::POLICY_PUBLIC) != 0) {
    policies.emplace_back(kPolicyPublic);
  }

  if ((bitmask & PolicyFlags::POLICY_PRODUCT_PARTITION) != 0) {
    policies.emplace_back(kPolicyProduct);
  }

  if ((bitmask & PolicyFlags::POLICY_SIGNATURE) != 0) {
    policies.emplace_back(kPolicySignature);
  }

  if ((bitmask & PolicyFlags::POLICY_SYSTEM_PARTITION) != 0) {
    policies.emplace_back(kPolicySystem);
  }

  if ((bitmask & PolicyFlags::POLICY_VENDOR_PARTITION) != 0) {
    policies.emplace_back(kPolicyVendor);
  }

  return policies;
}

}  // namespace android::idmap2
