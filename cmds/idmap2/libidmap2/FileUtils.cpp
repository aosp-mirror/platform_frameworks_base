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

#include "idmap2/FileUtils.h"

#include <random>
#include <string>
#include <string_view>

#include "android-base/file.h"
#include "android-base/macros.h"
#include "android-base/strings.h"
#include "private/android_filesystem_config.h"

namespace android::idmap2::utils {

#ifdef __ANDROID__
bool UidHasWriteAccessToPath(uid_t uid, const std::string& path) {
  // resolve symlinks and relative paths; the directories must exist
  std::string canonical_path;
  if (!base::Realpath(base::Dirname(path), &canonical_path)) {
    return false;
  }

  if (base::StartsWith(canonical_path, kIdmapCacheDir) &&
      (canonical_path.size() == kIdmapCacheDir.size() ||
       canonical_path[kIdmapCacheDir.size()] == '/')) {
    // limit access to /data/resource-cache to root and system
    return uid == AID_ROOT || uid == AID_SYSTEM;
  }
  return true;
}
#else
bool UidHasWriteAccessToPath(uid_t uid ATTRIBUTE_UNUSED, const std::string& path ATTRIBUTE_UNUSED) {
  return true;
}
#endif

std::string RandomStringForPath(size_t length) {
  constexpr std::string_view kChars =
      "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ";

  std::string out_rand;
  out_rand.resize(length);

  static thread_local std::random_device rd;
  std::uniform_int_distribution<int> dist(0, kChars.size() - 1);
  for (size_t i = 0; i < length; i++) {
    out_rand[i] = kChars[dist(rd)];
  }
  return out_rand;
}

}  // namespace android::idmap2::utils
