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

#ifndef AAPT_IO_FILESYSTEM_H
#define AAPT_IO_FILESYSTEM_H

#include <map>

#include "io/File.h"

namespace aapt {
namespace io {

// A regular file from the file system. Uses mmap to open the data.
class RegularFile : public IFile {
 public:
  explicit RegularFile(const Source& source);

  std::unique_ptr<IData> OpenAsData() override;
  std::unique_ptr<io::InputStream> OpenInputStream() override;
  const Source& GetSource() const override;

 private:
  DISALLOW_COPY_AND_ASSIGN(RegularFile);

  Source source_;
};

class FileCollection;

class FileCollectionIterator : public IFileCollectionIterator {
 public:
  explicit FileCollectionIterator(FileCollection* collection);

  bool HasNext() override;
  io::IFile* Next() override;

 private:
  DISALLOW_COPY_AND_ASSIGN(FileCollectionIterator);

  std::map<std::string, std::unique_ptr<IFile>>::const_iterator current_, end_;
};

// An IFileCollection representing the file system.
class FileCollection : public IFileCollection {
 public:
  FileCollection() = default;

  /** Creates a file collection containing all files contained in the specified root directory. */
  static std::unique_ptr<FileCollection> Create(const android::StringPiece& path,
                                                std::string* outError);

  // Adds a file located at path. Returns the IFile representation of that file.
  IFile* InsertFile(const android::StringPiece& path);
  IFile* FindFile(const android::StringPiece& path) override;
  std::unique_ptr<IFileCollectionIterator> Iterator() override;
  char GetDirSeparator() override;

 private:
  DISALLOW_COPY_AND_ASSIGN(FileCollection);

  friend class FileCollectionIterator;

  std::map<std::string, std::unique_ptr<IFile>> files_;
};

}  // namespace io
}  // namespace aapt

#endif  // AAPT_IO_FILESYSTEM_H
