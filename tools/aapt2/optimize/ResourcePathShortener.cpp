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

#include "optimize/ResourcePathShortener.h"

#include <set>
#include <unordered_set>

#include "androidfw/StringPiece.h"

#include "ResourceTable.h"
#include "ValueVisitor.h"
#include "util/Util.h"


static const std::string base64_chars =
             "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
             "abcdefghijklmnopqrstuvwxyz"
             "0123456789-_";

namespace aapt {

ResourcePathShortener::ResourcePathShortener(
    std::map<std::string, std::string>& path_map_out)
    : path_map_(path_map_out) {
}

std::string ShortenFileName(const android::StringPiece& file_path, int output_length) {
  std::size_t hash_num = std::hash<android::StringPiece>{}(file_path);
  std::string result = "";
  // Convert to (modified) base64 so that it is a proper file path.
  for (int i = 0; i < output_length; i++) {
    uint8_t sextet = hash_num & 0x3f;
    hash_num >>= 6;
    result += base64_chars[sextet];
  }
  return result;
}


// Return the optimal hash length such that at most 10% of resources collide in
// their shortened path.
// Reference: http://matt.might.net/articles/counting-hash-collisions/
int OptimalShortenedLength(int num_resources) {
  if (num_resources > 4000) {
    return 3;
  } else {
    return 2;
  }
}

std::string GetShortenedPath(const android::StringPiece& shortened_filename,
    const android::StringPiece& extension, int collision_count) {
  std::string shortened_path = "res/" + shortened_filename.to_string();
  if (collision_count > 0) {
    shortened_path += std::to_string(collision_count);
  }
  shortened_path += extension;
  return shortened_path;
}

// implement custom comparator of FileReference pointers so as to use the
// underlying filepath as key rather than the integer address. This is to ensure
// determinism of output for colliding files.
struct PathComparator {
    bool operator() (const FileReference* lhs, const FileReference* rhs) const {
        return lhs->path->compare(*rhs->path);
    }
};

bool ResourcePathShortener::Consume(IAaptContext* context, ResourceTable* table) {
  // used to detect collisions
  std::unordered_set<std::string> shortened_paths;
  std::set<FileReference*, PathComparator> file_refs;
  for (auto& package : table->packages) {
    for (auto& type : package->types) {
      for (auto& entry : type->entries) {
        for (auto& config_value : entry->values) {
          FileReference* file_ref = ValueCast<FileReference>(config_value->value.get());
          if (file_ref) {
            file_refs.insert(file_ref);
          }
        }
      }
    }
  }
  int num_chars = OptimalShortenedLength(file_refs.size());
  for (auto& file_ref : file_refs) {
    android::StringPiece res_subdir, actual_filename, extension;
    util::ExtractResFilePathParts(*file_ref->path, &res_subdir, &actual_filename, &extension);

    // Android detects ColorStateLists via pathname, skip res/color*
    if (util::StartsWith(res_subdir, "res/color"))
      continue;

    std::string shortened_filename = ShortenFileName(*file_ref->path, num_chars);
    int collision_count = 0;
    std::string shortened_path = GetShortenedPath(shortened_filename, extension, collision_count);
    while (shortened_paths.find(shortened_path) != shortened_paths.end()) {
      collision_count++;
      shortened_path = GetShortenedPath(shortened_filename, extension, collision_count);
    }
    shortened_paths.insert(shortened_path);
    path_map_.insert({*file_ref->path, shortened_path});
    file_ref->path = table->string_pool.MakeRef(shortened_path, file_ref->path.GetContext());
  }
  return true;
}

}  // namespace aapt
