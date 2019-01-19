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
#include <ostream>
#include <string>

#include "android-base/macros.h"
#include "androidfw/AssetManager2.h"

#include "idmap2/Idmap.h"
#include "idmap2/Result.h"
#include "idmap2/ZipFile.h"

namespace android::idmap2::utils {

struct OverlayManifestInfo {
  std::string target_package;  // NOLINT(misc-non-private-member-variables-in-classes)
  std::string target_name;     // NOLINT(misc-non-private-member-variables-in-classes)
  bool is_static;              // NOLINT(misc-non-private-member-variables-in-classes)
  int priority = -1;           // NOLINT(misc-non-private-member-variables-in-classes)
};

Result<OverlayManifestInfo> ExtractOverlayManifestInfo(const std::string& path,
                                                       std::ostream& out_error,
                                                       bool assert_overlay = true);

Result<std::string> WARN_UNUSED ResToTypeEntryName(const AssetManager2& am, ResourceId resid);

}  // namespace android::idmap2::utils

#endif  // IDMAP2_INCLUDE_IDMAP2_RESOURCEUTILS_H_
