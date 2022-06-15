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

#ifndef AAPT_FILES_H
#define AAPT_FILES_H

#include <memory>
#include <string>
#include <unordered_set>
#include <vector>

#include "android-base/macros.h"
#include "androidfw/StringPiece.h"
#include "utils/FileMap.h"

#include "Diagnostics.h"
#include "Maybe.h"
#include "Source.h"

namespace aapt {
namespace file {

#ifdef _WIN32
constexpr const char sDirSep = '\\';
constexpr const char sPathSep = ';';
#else
constexpr const char sDirSep = '/';
constexpr const char sPathSep = ':';
#endif

enum class FileType {
  kUnknown = 0,
  kNonexistant,
  kRegular,
  kDirectory,
  kCharDev,
  kBlockDev,
  kFifo,
  kSymlink,
  kSocket,
};

FileType GetFileType(const std::string& path);

// Appends a path to `base`, separated by the directory separator.
void AppendPath(std::string* base, android::StringPiece part);

// Concatenates the list of paths and separates each part with the directory separator.
std::string BuildPath(std::vector<const android::StringPiece>&& args);

// Makes all the directories in `path`. The last element in the path is interpreted as a directory.
bool mkdirs(const std::string& path);

// Returns all but the last part of the path.
android::StringPiece GetStem(const android::StringPiece& path);

// Returns the last part of the path with extension.
android::StringPiece GetFilename(const android::StringPiece& path);

// Returns the extension of the path. This is the entire string after the first '.' of the last part
// of the path.
android::StringPiece GetExtension(const android::StringPiece& path);

// Returns whether or not the name of the file or directory is a hidden file name
bool IsHidden(const android::StringPiece& path);

// Converts a package name (com.android.app) to a path: com/android/app
std::string PackageToPath(const android::StringPiece& package);

// Creates a FileMap for the file at path.
Maybe<android::FileMap> MmapPath(const std::string& path, std::string* out_error);

// Reads the file at path and appends each line to the outArgList vector.
bool AppendArgsFromFile(const android::StringPiece& path, std::vector<std::string>* out_arglist,
                        std::string* out_error);

// Reads the file at path and appends each line to the outargset set.
bool AppendSetArgsFromFile(const android::StringPiece& path,
                        std::unordered_set<std::string>* out_argset, std::string* out_error);

// Filter that determines which resource files/directories are
// processed by AAPT. Takes a pattern string supplied by the user.
// Pattern format is specified in the FileFilter::SetPattern() method.
class FileFilter {
 public:
  explicit FileFilter(IDiagnostics* diag) : diag_(diag) {}

  // Patterns syntax:
  // - Delimiter is :
  // - Entry can start with the flag ! to avoid printing a warning
  //   about the file being ignored.
  // - Entry can have the flag "<dir>" to match only directories
  //   or <file> to match only files. Default is to match both.
  // - Entry can be a simplified glob "<prefix>*" or "*<suffix>"
  //   where prefix/suffix must have at least 1 character (so that
  //   we don't match a '*' catch-all pattern.)
  // - The special filenames "." and ".." are always ignored.
  // - Otherwise the full string is matched.
  // - match is not case-sensitive.
  bool SetPattern(const android::StringPiece& pattern);

  // Applies the filter, returning true for pass, false for fail.
  bool operator()(const std::string& filename, FileType type) const;

 private:
  DISALLOW_COPY_AND_ASSIGN(FileFilter);

  IDiagnostics* diag_;
  std::vector<std::string> pattern_tokens_;
};

// Returns a list of files relative to the directory identified by `path`.
// An optional FileFilter filters out any files that don't pass.
Maybe<std::vector<std::string>> FindFiles(const android::StringPiece& path, IDiagnostics* diag,
                                          const FileFilter* filter = nullptr);

}  // namespace file
}  // namespace aapt

#endif  // AAPT_FILES_H
