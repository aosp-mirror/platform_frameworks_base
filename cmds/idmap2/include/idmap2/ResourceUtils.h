/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef IDMAP2_INCLUDE_IDMAP2_RESOURCEUTILS_H_
#define IDMAP2_INCLUDE_IDMAP2_RESOURCEUTILS_H_

#include <optional>
#include <string>

#include "androidfw/AssetManager2.h"
#include "idmap2/Result.h"
#include "idmap2/ZipFile.h"

namespace android::idmap2 {

// use typedefs to let the compiler warn us about implicit casts
typedef uint32_t ResourceId;  // 0xpptteeee
typedef uint8_t PackageId;    // pp in 0xpptteeee
typedef uint8_t TypeId;       // tt in 0xpptteeee
typedef uint16_t EntryId;     // eeee in 0xpptteeee

#define EXTRACT_TYPE(resid) ((0x00ff0000 & (resid)) >> 16)
#define EXTRACT_ENTRY(resid) (0x0000ffff & (resid))

namespace utils {

// Returns whether the Res_value::data_type represents a dynamic or regular resource reference.
bool IsReference(uint8_t data_type);

// Converts the Res_value::data_type to a human-readable string representation.
StringPiece DataTypeToString(uint8_t data_type);

struct OverlayManifestInfo {
  std::string target_package;               // NOLINT(misc-non-private-member-variables-in-classes)
  std::string target_name;                  // NOLINT(misc-non-private-member-variables-in-classes)
  std::string requiredSystemPropertyName;   // NOLINT(misc-non-private-member-variables-in-classes)
  std::string requiredSystemPropertyValue;  // NOLINT(misc-non-private-member-variables-in-classes)
  uint32_t resource_mapping;                // NOLINT(misc-non-private-member-variables-in-classes)
  bool is_static;                           // NOLINT(misc-non-private-member-variables-in-classes)
  int priority = -1;                        // NOLINT(misc-non-private-member-variables-in-classes)
};

Result<OverlayManifestInfo> ExtractOverlayManifestInfo(const std::string& path,
                                                       bool assert_overlay = true);

Result<std::string> ResToTypeEntryName(const AssetManager2& am, ResourceId resid);

}  // namespace utils

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_RESOURCEUTILS_H_
