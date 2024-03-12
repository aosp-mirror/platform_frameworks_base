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

#define _POSIX_THREAD_SAFE_FUNCTIONS  // For mingw localtime_r().

#include "io/FileSystem.h"

#include <dirent.h>
#include <sys/stat.h>

#include "android-base/errors.h"
#include "androidfw/FileStream.h"
#include "androidfw/Source.h"
#include "androidfw/StringPiece.h"
#include "util/Files.h"
#include "util/Util.h"
#include "utils/FileMap.h"

using ::android::StringPiece;
using ::android::base::SystemErrorCodeToString;

namespace aapt {
namespace io {

RegularFile::RegularFile(const android::Source& source) : source_(source) {
}

std::unique_ptr<IData> RegularFile::OpenAsData() {
  android::FileMap map;
  if (std::optional<android::FileMap> map = file::MmapPath(source_.path, nullptr)) {
    if (map.value().getDataPtr() && map.value().getDataLength() > 0) {
      return util::make_unique<MmappedData>(std::move(map.value()));
    }
    return util::make_unique<EmptyData>();
  }
  return {};
}

std::unique_ptr<android::InputStream> RegularFile::OpenInputStream() {
  return util::make_unique<android::FileInputStream>(source_.path);
}

const android::Source& RegularFile::GetSource() const {
  return source_;
}

bool RegularFile::GetModificationTime(struct tm* buf) const {
  if (buf == nullptr) {
    return false;
  }
  struct stat stat_buf;
  if (stat(source_.path.c_str(), &stat_buf) != 0) {
    return false;
  }

  struct tm* ptm;
  struct tm tm_result;
  ptm = localtime_r(&stat_buf.st_mtime, &tm_result);

  *buf = *ptm;
  return true;
}

FileCollectionIterator::FileCollectionIterator(FileCollection* collection)
    : current_(collection->files_.begin()), end_(collection->files_.end()) {}

bool FileCollectionIterator::HasNext() {
  return current_ != end_;
}

IFile* FileCollectionIterator::Next() {
  IFile* result = current_->second.get();
  ++current_;
  return result;
}

std::unique_ptr<FileCollection> FileCollection::Create(android::StringPiece root,
                                                       std::string* outError) {
  std::unique_ptr<FileCollection> collection =
      std::unique_ptr<FileCollection>(new FileCollection());

  std::unique_ptr<DIR, decltype(closedir) *> d(opendir(root.data()), closedir);
  if (!d) {
    *outError = "failed to open directory: " + SystemErrorCodeToString(errno);
    return nullptr;
  }

  std::vector<std::string> sorted_files;
  while (struct dirent *entry = readdir(d.get())) {
    std::string prefix_path(root);
    file::AppendPath(&prefix_path, entry->d_name);

    // The directory to iterate over looking for files
    if (file::GetFileType(prefix_path) != file::FileType::kDirectory
        || file::IsHidden(prefix_path)) {
      continue;
    }

    std::unique_ptr<DIR, decltype(closedir)*> subdir(opendir(prefix_path.data()), closedir);
    if (!subdir) {
      *outError = "failed to open directory: " + SystemErrorCodeToString(errno);
      return nullptr;
    }

    while (struct dirent* leaf_entry = readdir(subdir.get())) {
      std::string full_path = prefix_path;
      file::AppendPath(&full_path, leaf_entry->d_name);

      // Do not add folders to the file collection
      if (file::GetFileType(full_path) == file::FileType::kDirectory
          || file::IsHidden(full_path)) {
        continue;
      }

      sorted_files.push_back(full_path);
    }
  }

  std::sort(sorted_files.begin(), sorted_files.end());
  for (const std::string& full_path : sorted_files) {
    collection->InsertFile(full_path);
  }

  return collection;
}

IFile* FileCollection::InsertFile(StringPiece path) {
  auto file = util::make_unique<RegularFile>(android::Source(path));
  auto it = files_.lower_bound(path);
  if (it != files_.end() && it->first == path) {
    it->second = std::move(file);
  } else {
    it = files_.emplace_hint(it, path, std::move(file));
  }
  return it->second.get();
}

IFile* FileCollection::FindFile(StringPiece path) {
  auto iter = files_.find(path);
  if (iter != files_.end()) {
    return iter->second.get();
  }
  return nullptr;
}

std::unique_ptr<IFileCollectionIterator> FileCollection::Iterator() {
  return util::make_unique<FileCollectionIterator>(this);
}

char FileCollection::GetDirSeparator() {
  return file::sDirSep;
}

}  // namespace io
}  // namespace aapt
