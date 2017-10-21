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

#include "androidfw/StringPiece.h"
#include "utils/FileMap.h"

#include "Source.h"
#include "io/FileStream.h"
#include "util/Files.h"
#include "util/Maybe.h"
#include "util/Util.h"

using ::android::StringPiece;

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

}  // namespace io
}  // namespace aapt
