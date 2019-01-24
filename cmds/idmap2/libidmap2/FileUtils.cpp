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

#include <dirent.h>
#include <sys/types.h>
#include <unistd.h>

#include <cerrno>
#include <climits>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <memory>
#include <string>
#include <utility>
#include <vector>

#include "android-base/file.h"
#include "android-base/macros.h"
#include "android-base/stringprintf.h"
#include "private/android_filesystem_config.h"

#include "idmap2/FileUtils.h"

namespace android::idmap2::utils {

std::unique_ptr<std::vector<std::string>> FindFiles(const std::string& root, bool recurse,
                                                    const FindFilesPredicate& predicate) {
  DIR* dir = opendir(root.c_str());
  if (dir == nullptr) {
    return nullptr;
  }
  std::unique_ptr<std::vector<std::string>> vector(new std::vector<std::string>());
  struct dirent* dirent;
  while ((dirent = readdir(dir)) != nullptr) {
    const std::string path = root + "/" + dirent->d_name;
    if (predicate(dirent->d_type, path)) {
      vector->push_back(path);
    }
    if (recurse && dirent->d_type == DT_DIR && strcmp(dirent->d_name, ".") != 0 &&
        strcmp(dirent->d_name, "..") != 0) {
      auto sub_vector = FindFiles(path, recurse, predicate);
      if (!sub_vector) {
        closedir(dir);
        return nullptr;
      }
      vector->insert(vector->end(), sub_vector->begin(), sub_vector->end());
    }
  }
  closedir(dir);

  return vector;
}

std::unique_ptr<std::string> ReadFile(const std::string& path) {
  std::unique_ptr<std::string> str(new std::string());
  std::ifstream fin(path);
  str->append({std::istreambuf_iterator<char>(fin), std::istreambuf_iterator<char>()});
  fin.close();
  return str;
}

std::unique_ptr<std::string> ReadFile(int fd) {
  static constexpr const size_t kBufSize = 1024;

  std::unique_ptr<std::string> str(new std::string());
  char buf[kBufSize];
  ssize_t r;
  while ((r = read(fd, buf, sizeof(buf))) > 0) {
    str->append(buf, r);
  }
  return r == 0 ? std::move(str) : nullptr;
}

#ifdef __ANDROID__
bool UidHasWriteAccessToPath(uid_t uid, const std::string& path) {
  // resolve symlinks and relative paths; the directories must exist
  std::string canonical_path;
  if (!base::Realpath(base::Dirname(path), &canonical_path)) {
    return false;
  }

  const std::string cache_subdir = base::StringPrintf("%s/", kIdmapCacheDir);
  if (canonical_path == kIdmapCacheDir ||
      canonical_path.compare(0, cache_subdir.size(), cache_subdir) == 0) {
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

}  // namespace android::idmap2::utils
