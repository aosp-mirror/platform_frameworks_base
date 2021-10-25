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

#ifndef IDMAP2_INCLUDE_IDMAP2_POLICYUTILS_H_
#define IDMAP2_INCLUDE_IDMAP2_POLICYUTILS_H_

#include <string>
#include <vector>

#include "androidfw/ResourceTypes.h"
#include "idmap2/Policies.h"
#include "idmap2/Result.h"

using PolicyBitmask = android::ResTable_overlayable_policy_header::PolicyBitmask;

namespace android::idmap2::utils {

// Returns a Result object containing a policy flag bitmask built from a list of policy strings.
// On error will contain a human readable message listing the invalid policies.
Result<PolicyBitmask> PoliciesToBitmaskResult(const std::vector<std::string>& policies);

// Converts a bitmask of policy flags into a list of their string representation as would be written
// into XML
std::vector<std::string> BitmaskToPolicies(const PolicyBitmask& bitmask);

}  // namespace android::idmap2::utils

#endif  // IDMAP2_INCLUDE_IDMAP2_POLICYUTILS_H_
