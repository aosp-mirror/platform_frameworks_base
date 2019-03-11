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

#include <string>
#include <vector>

#include "androidfw/ResourceTypes.h"
#include "androidfw/StringPiece.h"

#include "Result.h"

#ifndef IDMAP2_INCLUDE_IDMAP2_POLICIES_H_
#define IDMAP2_INCLUDE_IDMAP2_POLICIES_H_

namespace android::idmap2 {

constexpr const char* kPolicyPublic = "public";
constexpr const char* kPolicyProduct = "product";
constexpr const char* kPolicySystem = "system";
constexpr const char* kPolicyVendor = "vendor";
constexpr const char* kPolicySignature = "signature";

using PolicyFlags = ResTable_overlayable_policy_header::PolicyFlags;
using PolicyBitmask = uint32_t;

// Parses the string representations of policies into a bitmask.
Result<PolicyBitmask> PoliciesToBitmask(const std::vector<std::string>& policies);

// Retrieves the string representations of policies in the bitmask.
std::vector<std::string> BitmaskToPolicies(const PolicyBitmask& bitmask);

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_POLICIES_H_
