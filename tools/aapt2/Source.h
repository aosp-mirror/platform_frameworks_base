/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_SOURCE_H
#define AAPT_SOURCE_H

#include <optional>
#include <ostream>
#include <string>

#include "android-base/stringprintf.h"
#include "androidfw/StringPiece.h"

namespace aapt {

// Represents a file on disk. Used for logging and showing errors.
struct Source {
  std::string path;
  std::optional<size_t> line;
  std::optional<std::string> archive;

  Source() = default;

  inline Source(const android::StringPiece& path) : path(path.to_string()) {  // NOLINT(implicit)
  }

  inline Source(const android::StringPiece& path, const android::StringPiece& archive)
      : path(path.to_string()), archive(archive.to_string()) {}

  inline Source(const android::StringPiece& path, size_t line)
      : path(path.to_string()), line(line) {}

  inline Source WithLine(size_t line) const {
    return Source(path, line);
  }

  std::string to_string() const {
    std::string s = path;
    if (archive) {
      s = ::android::base::StringPrintf("%s@%s", archive.value().c_str(), s.c_str());
    }
    if (line) {
      s = ::android::base::StringPrintf("%s:%zd", s.c_str(), line.value());
    }
    return s;
  }
};

//
// Implementations
//

inline ::std::ostream& operator<<(::std::ostream& out, const Source& source) {
  return out << source.to_string();
}

inline bool operator==(const Source& lhs, const Source& rhs) {
  return lhs.path == rhs.path && lhs.line == rhs.line;
}

inline bool operator<(const Source& lhs, const Source& rhs) {
  int cmp = lhs.path.compare(rhs.path);
  if (cmp < 0) return true;
  if (cmp > 0) return false;
  if (lhs.line) {
    if (rhs.line) {
      return lhs.line.value() < rhs.line.value();
    }
    return false;
  }
  return bool(rhs.line);
}

}  // namespace aapt

#endif  // AAPT_SOURCE_H
