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

#include <iterator>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "androidfw/ResourceTypes.h"

#include "idmap2/Idmap.h"
#include "idmap2/Policies.h"
#include "idmap2/Result.h"

namespace android::idmap2 {

namespace {

const std::map<android::StringPiece, PolicyFlags> kStringToFlag = {
    {"public", PolicyFlags::POLICY_PUBLIC},
    {"product", PolicyFlags::POLICY_PRODUCT_PARTITION},
    {"system", PolicyFlags::POLICY_SYSTEM_PARTITION},
    {"vendor", PolicyFlags::POLICY_VENDOR_PARTITION},
    {"signature", PolicyFlags::POLICY_SIGNATURE},
};
}  // namespace

Result<PolicyBitmask> PoliciesToBitmask(const std::vector<std::string>& policies,
                                        std::ostream& err) {
  PolicyBitmask bitmask = 0;
  for (const std::string& policy : policies) {
    const auto iter = kStringToFlag.find(policy);
    if (iter != kStringToFlag.end()) {
      bitmask |= iter->second;
    } else {
      err << "error: unknown policy \"" << policy << "\"";
      return kResultError;
    }
  }

  return Result<PolicyBitmask>(bitmask);
}

}  // namespace android::idmap2
