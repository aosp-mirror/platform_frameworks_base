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

#ifndef AAPT_IO_FILE_H
#define AAPT_IO_FILE_H

#include <list>
#include <memory>
#include <vector>

#include "android-base/macros.h"

#include "Source.h"
#include "io/Data.h"
#include "util/Util.h"

namespace aapt {
namespace io {

// Interface for a file, which could be a real file on the file system, or a
// file inside a ZIP archive.
class IFile {
 public:
  virtual ~IFile() = default;

  // Open the file and return it as a block of contiguous memory. How this
  // occurs is implementation dependent. For example, if this is a file on the file
  // system, it may simply mmap the contents. If this file represents a compressed file in a
  // ZIP archive, it may need to inflate it to memory, incurring a copy.
  // Returns nullptr on failure.
  virtual std::unique_ptr<IData> OpenAsData() = 0;

  virtual std::unique_ptr<io::InputStream> OpenInputStream() = 0;

  // Returns the source of this file. This is for presentation to the user and
  // may not be a valid file system path (for example, it may contain a '@' sign to separate
  // the files within a ZIP archive from the path to the containing ZIP archive.
  virtual const Source& GetSource() const = 0;

  IFile* CreateFileSegment(size_t offset, size_t len);

  // Returns whether the file was compressed before it was stored in memory.
  virtual bool WasCompressed() {
    return false;
  }

 private:
  // Any segments created from this IFile need to be owned by this IFile, so
  // keep them
  // in a list. This will never be read, so we prefer better insertion
  // performance
  // than cache locality, hence the list.
  std::list<std::unique_ptr<IFile>> segments_;
};

// An IFile that wraps an underlying IFile but limits it to a subsection of that file.
class FileSegment : public IFile {
 public:
  explicit FileSegment(IFile* file, size_t offset, size_t len)
      : file_(file), offset_(offset), len_(len) {}

  std::unique_ptr<IData> OpenAsData() override;
  std::unique_ptr<io::InputStream> OpenInputStream() override;

  const Source& GetSource() const override {
    return file_->GetSource();
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(FileSegment);

  IFile* file_;
  size_t offset_;
  size_t len_;
};

class IFileCollectionIterator {
 public:
  virtual ~IFileCollectionIterator() = default;

  virtual bool HasNext() = 0;
  virtual IFile* Next() = 0;
};

// Interface for a collection of files, all of which share a common source. That source may
// simply be the filesystem, or a ZIP archive.
class IFileCollection {
 public:
  virtual ~IFileCollection() = default;

  virtual IFile* FindFile(const android::StringPiece& path) = 0;
  virtual std::unique_ptr<IFileCollectionIterator> Iterator() = 0;
};

}  // namespace io
}  // namespace aapt

#endif /* AAPT_IO_FILE_H */
