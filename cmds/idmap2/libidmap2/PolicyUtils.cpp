/*
 * Copyright (C) 2020 The Android Open Source Project
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

#include "include/idmap2/PolicyUtils.h"

#include <sstream>

#include "android-base/strings.h"
#include "idmap2/Policies.h"

using android::idmap2::policy::kPolicyStringToFlag;

namespace android::idmap2::utils {

Result<PolicyBitmask> PoliciesToBitmaskResult(const std::vector<std::string>& policies) {
  std::vector<std::string> unknown_policies;
  PolicyBitmask bitmask = 0;
  for (const std::string& policy : policies) {
    const auto result = std::find_if(kPolicyStringToFlag.begin(), kPolicyStringToFlag.end(),
                                     [policy](const auto& it) { return policy == it.first; });
    if (result != kPolicyStringToFlag.end()) {
      bitmask |= result->second;
    } else {
      unknown_policies.emplace_back(policy.empty() ? "empty" : policy);
    }
  }

  if (unknown_policies.empty()) {
    return Result<PolicyBitmask>(bitmask);
  }

  auto prefix = unknown_policies.size() == 1 ? "policy" : "policies";
  return Error("unknown %s: \"%s\"", prefix, android::base::Join(unknown_policies, ",").c_str());
}

std::vector<std::string> BitmaskToPolicies(const PolicyBitmask& bitmask) {
  std::vector<std::string> policies;

  for (const auto& policy : kPolicyStringToFlag) {
    if ((bitmask & policy.second) != 0) {
      policies.emplace_back(policy.first.to_string());
    }
  }

  return policies;
}

}  // namespace android::idmap2::utils
