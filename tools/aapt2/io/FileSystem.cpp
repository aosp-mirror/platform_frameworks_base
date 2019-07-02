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

#include "io/FileSystem.h"

#include <dirent.h>

#include "android-base/errors.h"
#include "androidfw/StringPiece.h"
#include "utils/FileMap.h"

#include "Source.h"
#include "io/FileStream.h"
#include "util/Files.h"
#include "util/Maybe.h"
#include "util/Util.h"

using ::android::StringPiece;
using ::android::base::SystemErrorCodeToString;

namespace aapt {
namespace io {

RegularFile::RegularFile(const Source& source) : source_(source) {}

std::unique_ptr<IData> RegularFile::OpenAsData() {
  android::FileMap map;
  if (Maybe<android::FileMap> map = file::MmapPath(source_.path, nullptr)) {
    if (map.value().getDataPtr() && map.value().getDataLength() > 0) {
      return util::make_unique<MmappedData>(std::move(map.value()));
    }
    return util::make_unique<EmptyData>();
  }
  return {};
}

std::unique_ptr<io::InputStream> RegularFile::OpenInputStream() {
  return util::make_unique<FileInputStream>(source_.path);
}

const Source& RegularFile::GetSource() const {
  return source_;
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

std::unique_ptr<FileCollection> FileCollection::Create(const android::StringPiece& root,
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
    std::string prefix_path = root.to_string();
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

IFile* FileCollection::InsertFile(const StringPiece& path) {
  return (files_[path.to_string()] = util::make_unique<RegularFile>(Source(path))).get();
}

IFile* FileCollection::FindFile(const StringPiece& path) {
  auto iter = files_.find(path.to_string());
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
