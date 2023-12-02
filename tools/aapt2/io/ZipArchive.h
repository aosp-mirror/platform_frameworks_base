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

#ifndef AAPT_IO_ZIPARCHIVE_H
#define AAPT_IO_ZIPARCHIVE_H

#include "ziparchive/zip_archive.h"

#include <map>

#include "androidfw/StringPiece.h"

#include "io/File.h"

namespace aapt {
namespace io {

// An IFile representing a file within a ZIP archive. If the file is compressed, it is uncompressed
// and copied into memory when opened. Otherwise it is mmapped from the ZIP archive.
class ZipFile : public IFile {
 public:
  ZipFile(::ZipArchiveHandle handle, const ::ZipEntry& entry, const android::Source& source);

  std::unique_ptr<IData> OpenAsData() override;
  std::unique_ptr<android::InputStream> OpenInputStream() override;
  const android::Source& GetSource() const override;
  bool WasCompressed() override;
  bool GetModificationTime(struct tm* buf) const override;

 private:
  ::ZipArchiveHandle zip_handle_;
  ::ZipEntry zip_entry_;
  android::Source source_;
};

class ZipFileCollection;

class ZipFileCollectionIterator : public IFileCollectionIterator {
 public:
  explicit ZipFileCollectionIterator(ZipFileCollection* collection);

  bool HasNext() override;
  io::IFile* Next() override;

 private:
  std::vector<std::unique_ptr<IFile>>::const_iterator current_, end_;
};

// An IFileCollection that represents a ZIP archive and the entries within it.
class ZipFileCollection : public IFileCollection {
 public:
  static std::unique_ptr<ZipFileCollection> Create(android::StringPiece path,
                                                   std::string* outError);

  io::IFile* FindFile(android::StringPiece path) override;
  std::unique_ptr<IFileCollectionIterator> Iterator() override;
  char GetDirSeparator() override;

  ~ZipFileCollection() override;

 private:
  friend class ZipFileCollectionIterator;
  ZipFileCollection();

  ZipArchiveHandle handle_;
  std::vector<std::unique_ptr<IFile>> files_;
  std::map<std::string, IFile*, std::less<>> files_by_name_;
};

}  // namespace io
}  // namespace aapt

#endif /* AAPT_IO_ZIPARCHIVE_H */
