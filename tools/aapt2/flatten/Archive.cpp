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

#include "flatten/Archive.h"

#include <cstdio>
#include <memory>
#include <string>
#include <vector>

#include "android-base/macros.h"
#include "ziparchive/zip_writer.h"

#include "util/Files.h"
#include "util/StringPiece.h"

namespace aapt {

namespace {

class DirectoryWriter : public IArchiveWriter {
 public:
  DirectoryWriter() = default;

  bool Open(IDiagnostics* diag, const StringPiece& out_dir) {
    dir_ = out_dir.ToString();
    file::FileType type = file::GetFileType(dir_);
    if (type == file::FileType::kNonexistant) {
      diag->Error(DiagMessage() << "directory " << dir_ << " does not exist");
      return false;
    } else if (type != file::FileType::kDirectory) {
      diag->Error(DiagMessage() << dir_ << " is not a directory");
      return false;
    }
    return true;
  }

  bool StartEntry(const StringPiece& path, uint32_t flags) override {
    if (file_) {
      return false;
    }

    std::string full_path = dir_;
    file::AppendPath(&full_path, path);
    file::mkdirs(file::GetStem(full_path));

    file_ = {fopen(full_path.data(), "wb"), fclose};
    if (!file_) {
      return false;
    }
    return true;
  }

  bool WriteEntry(const BigBuffer& buffer) override {
    if (!file_) {
      return false;
    }

    for (const BigBuffer::Block& b : buffer) {
      if (fwrite(b.buffer.get(), 1, b.size, file_.get()) != b.size) {
        file_.reset(nullptr);
        return false;
      }
    }
    return true;
  }

  bool WriteEntry(const void* data, size_t len) override {
    if (fwrite(data, 1, len, file_.get()) != len) {
      file_.reset(nullptr);
      return false;
    }
    return true;
  }

  bool FinishEntry() override {
    if (!file_) {
      return false;
    }
    file_.reset(nullptr);
    return true;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(DirectoryWriter);

  std::string dir_;
  std::unique_ptr<FILE, decltype(fclose)*> file_ = {nullptr, fclose};
};

class ZipFileWriter : public IArchiveWriter {
 public:
  ZipFileWriter() = default;

  bool Open(IDiagnostics* diag, const StringPiece& path) {
    file_ = {fopen(path.data(), "w+b"), fclose};
    if (!file_) {
      diag->Error(DiagMessage() << "failed to Open " << path << ": "
                                << strerror(errno));
      return false;
    }
    writer_ = util::make_unique<ZipWriter>(file_.get());
    return true;
  }

  bool StartEntry(const StringPiece& path, uint32_t flags) override {
    if (!writer_) {
      return false;
    }

    size_t zip_flags = 0;
    if (flags & ArchiveEntry::kCompress) {
      zip_flags |= ZipWriter::kCompress;
    }

    if (flags & ArchiveEntry::kAlign) {
      zip_flags |= ZipWriter::kAlign32;
    }

    int32_t result = writer_->StartEntry(path.data(), zip_flags);
    if (result != 0) {
      return false;
    }
    return true;
  }

  bool WriteEntry(const void* data, size_t len) override {
    int32_t result = writer_->WriteBytes(data, len);
    if (result != 0) {
      return false;
    }
    return true;
  }

  bool WriteEntry(const BigBuffer& buffer) override {
    for (const BigBuffer::Block& b : buffer) {
      int32_t result = writer_->WriteBytes(b.buffer.get(), b.size);
      if (result != 0) {
        return false;
      }
    }
    return true;
  }

  bool FinishEntry() override {
    int32_t result = writer_->FinishEntry();
    if (result != 0) {
      return false;
    }
    return true;
  }

  virtual ~ZipFileWriter() {
    if (writer_) {
      writer_->Finish();
    }
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ZipFileWriter);

  std::unique_ptr<FILE, decltype(fclose)*> file_ = {nullptr, fclose};
  std::unique_ptr<ZipWriter> writer_;
};

}  // namespace

std::unique_ptr<IArchiveWriter> CreateDirectoryArchiveWriter(
    IDiagnostics* diag, const StringPiece& path) {
  std::unique_ptr<DirectoryWriter> writer =
      util::make_unique<DirectoryWriter>();
  if (!writer->Open(diag, path)) {
    return {};
  }
  return std::move(writer);
}

std::unique_ptr<IArchiveWriter> CreateZipFileArchiveWriter(
    IDiagnostics* diag, const StringPiece& path) {
  std::unique_ptr<ZipFileWriter> writer = util::make_unique<ZipFileWriter>();
  if (!writer->Open(diag, path)) {
    return {};
  }
  return std::move(writer);
}

}  // namespace aapt
