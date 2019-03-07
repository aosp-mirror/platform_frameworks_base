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

#include "io/ZipArchive.h"

#include "utils/FileMap.h"
#include "ziparchive/zip_archive.h"

#include "Source.h"
#include "trace/TraceBuffer.h"
#include "util/Files.h"
#include "util/Util.h"

using ::android::StringPiece;

namespace aapt {
namespace io {

ZipFile::ZipFile(ZipArchiveHandle handle, const ZipEntry& entry,
                 const Source& source)
    : zip_handle_(handle), zip_entry_(entry), source_(source) {}

std::unique_ptr<IData> ZipFile::OpenAsData() {
  // The file will fail to be mmaped if it is empty
  if (zip_entry_.uncompressed_length == 0) {
    return util::make_unique<EmptyData>();
  }

  if (zip_entry_.method == kCompressStored) {
    int fd = GetFileDescriptor(zip_handle_);

    android::FileMap file_map;
    bool result = file_map.create(nullptr, fd, zip_entry_.offset,
                                  zip_entry_.uncompressed_length, true);
    if (!result) {
      return {};
    }
    return util::make_unique<MmappedData>(std::move(file_map));

  } else {
    std::unique_ptr<uint8_t[]> data =
        std::unique_ptr<uint8_t[]>(new uint8_t[zip_entry_.uncompressed_length]);
    int32_t result =
        ExtractToMemory(zip_handle_, &zip_entry_, data.get(),
                        static_cast<uint32_t>(zip_entry_.uncompressed_length));
    if (result != 0) {
      return {};
    }
    return util::make_unique<MallocData>(std::move(data),
                                         zip_entry_.uncompressed_length);
  }
}

std::unique_ptr<io::InputStream> ZipFile::OpenInputStream() {
  return OpenAsData();
}

const Source& ZipFile::GetSource() const {
  return source_;
}

bool ZipFile::WasCompressed() {
  return zip_entry_.method != kCompressStored;
}

ZipFileCollectionIterator::ZipFileCollectionIterator(
    ZipFileCollection* collection)
    : current_(collection->files_.begin()), end_(collection->files_.end()) {}

bool ZipFileCollectionIterator::HasNext() {
  return current_ != end_;
}

IFile* ZipFileCollectionIterator::Next() {
  IFile* result = current_->get();
  ++current_;
  return result;
}

ZipFileCollection::ZipFileCollection() : handle_(nullptr) {}

std::unique_ptr<ZipFileCollection> ZipFileCollection::Create(
    const StringPiece& path, std::string* out_error) {
  TRACE_CALL();
  constexpr static const int32_t kEmptyArchive = -6;

  std::unique_ptr<ZipFileCollection> collection =
      std::unique_ptr<ZipFileCollection>(new ZipFileCollection());

  int32_t result = OpenArchive(path.data(), &collection->handle_);
  if (result != 0) {
    // If a zip is empty, result will be an error code. This is fine and we
    // should
    // return an empty ZipFileCollection.
    if (result == kEmptyArchive) {
      return collection;
    }

    if (out_error) *out_error = ErrorCodeString(result);
    return {};
  }

  void* cookie = nullptr;
  result = StartIteration(collection->handle_, &cookie, nullptr, nullptr);
  if (result != 0) {
    if (out_error) *out_error = ErrorCodeString(result);
    return {};
  }

  using IterationEnder = std::unique_ptr<void, decltype(EndIteration)*>;
  IterationEnder iteration_ender(cookie, EndIteration);

  ZipString zip_entry_name;
  ZipEntry zip_data;
  while ((result = Next(cookie, &zip_data, &zip_entry_name)) == 0) {
    std::string zip_entry_path =
        std::string(reinterpret_cast<const char*>(zip_entry_name.name),
                    zip_entry_name.name_length);

    // Do not add folders to the file collection
    if (util::EndsWith(zip_entry_path, "/")) {
      continue;
    }

    std::unique_ptr<IFile> file = util::make_unique<ZipFile>(collection->handle_, zip_data,
        Source(zip_entry_path, path.to_string()));
    collection->files_by_name_[zip_entry_path] = file.get();
    collection->files_.push_back(std::move(file));
  }

  if (result != -1) {
    if (out_error) *out_error = ErrorCodeString(result);
    return {};
  }

  return collection;
}

IFile* ZipFileCollection::FindFile(const StringPiece& path) {
  auto iter = files_by_name_.find(path.to_string());
  if (iter != files_by_name_.end()) {
    return iter->second;
  }
  return nullptr;
}

std::unique_ptr<IFileCollectionIterator> ZipFileCollection::Iterator() {
  return util::make_unique<ZipFileCollectionIterator>(this);
}

char ZipFileCollection::GetDirSeparator() {
  // According to the zip file specification, section  4.4.17.1:
  // "All slashes MUST be forward slashes '/' as opposed to backwards slashes '\' for compatibility
  // with Amiga and UNIX file systems etc."
  return '/';
}

ZipFileCollection::~ZipFileCollection() {
  if (handle_) {
    CloseArchive(handle_);
  }
}

}  // namespace io
}  // namespace aapt
