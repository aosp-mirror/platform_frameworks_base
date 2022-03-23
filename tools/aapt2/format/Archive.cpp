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

#include "format/Archive.h"

#include <cstdio>
#include <memory>
#include <string>
#include <vector>

#include "android-base/errors.h"
#include "android-base/macros.h"
#include "android-base/utf8.h"
#include "androidfw/StringPiece.h"
#include "ziparchive/zip_writer.h"

#include "util/Files.h"

using ::android::StringPiece;
using ::android::base::SystemErrorCodeToString;

namespace aapt {

namespace {

class DirectoryWriter : public IArchiveWriter {
 public:
  DirectoryWriter() = default;

  bool Open(const StringPiece& out_dir) {
    dir_ = out_dir.to_string();
    file::FileType type = file::GetFileType(dir_);
    if (type == file::FileType::kNonexistant) {
      error_ = "directory does not exist";
      return false;
    } else if (type != file::FileType::kDirectory) {
      error_ = "not a directory";
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
    file::mkdirs(file::GetStem(full_path).to_string());

    file_ = {::android::base::utf8::fopen(full_path.c_str(), "wb"), fclose};
    if (!file_) {
      error_ = SystemErrorCodeToString(errno);
      return false;
    }
    return true;
  }

  bool Write(const void* data, int len) override {
    if (!file_) {
      return false;
    }

    if (fwrite(data, 1, len, file_.get()) != static_cast<size_t>(len)) {
      error_ = SystemErrorCodeToString(errno);
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

  bool WriteFile(const StringPiece& path, uint32_t flags, io::InputStream* in) override {
    if (!StartEntry(path, flags)) {
      return false;
    }

    const void* data = nullptr;
    size_t len = 0;
    while (in->Next(&data, &len)) {
      if (!Write(data, static_cast<int>(len))) {
        return false;
      }
    }

    if (in->HadError()) {
      error_ = in->GetError();
      return false;
    }

    return FinishEntry();
  }

  bool HadError() const override {
    return !error_.empty();
  }

  std::string GetError() const override {
    return error_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(DirectoryWriter);

  std::string dir_;
  std::unique_ptr<FILE, decltype(fclose)*> file_ = {nullptr, fclose};
  std::string error_;
};

class ZipFileWriter : public IArchiveWriter {
 public:
  ZipFileWriter() = default;

  bool Open(const StringPiece& path) {
    file_ = {::android::base::utf8::fopen(path.to_string().c_str(), "w+b"), fclose};
    if (!file_) {
      error_ = SystemErrorCodeToString(errno);
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
      error_ = ZipWriter::ErrorCodeString(result);
      return false;
    }
    return true;
  }

  bool Write(const void* data, int len) override {
    int32_t result = writer_->WriteBytes(data, len);
    if (result != 0) {
      error_ = ZipWriter::ErrorCodeString(result);
      return false;
    }
    return true;
  }

  bool FinishEntry() override {
    int32_t result = writer_->FinishEntry();
    if (result != 0) {
      error_ = ZipWriter::ErrorCodeString(result);
      return false;
    }
    return true;
  }

  bool WriteFile(const StringPiece& path, uint32_t flags, io::InputStream* in) override {
    while (true) {
      if (!StartEntry(path, flags)) {
        return false;
      }

      const void* data = nullptr;
      size_t len = 0;
      while (in->Next(&data, &len)) {
        if (!Write(data, static_cast<int>(len))) {
          return false;
        }
      }

      if (in->HadError()) {
        error_ = in->GetError();
        return false;
      }

      if (!FinishEntry()) {
        return false;
      }

      // Check to see if the file was compressed enough. This is preserving behavior of AAPT.
      if ((flags & ArchiveEntry::kCompress) != 0 && in->CanRewind()) {
        ZipWriter::FileEntry last_entry;
        int32_t result = writer_->GetLastEntry(&last_entry);
        CHECK(result == 0);
        if (last_entry.compressed_size + (last_entry.compressed_size / 10) >
            last_entry.uncompressed_size) {
          // The file was not compressed enough, rewind and store it uncompressed.
          if (!in->Rewind()) {
            // Well we tried, may as well keep what we had.
            return true;
          }

          int32_t result = writer_->DiscardLastEntry();
          if (result != 0) {
            error_ = ZipWriter::ErrorCodeString(result);
            return false;
          }
          flags &= ~ArchiveEntry::kCompress;

          continue;
        }
      }
      return true;
    }
  }

  bool HadError() const override {
    return !error_.empty();
  }

  std::string GetError() const override {
    return error_;
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
  std::string error_;
};

}  // namespace

std::unique_ptr<IArchiveWriter> CreateDirectoryArchiveWriter(IDiagnostics* diag,
                                                             const StringPiece& path) {
  std::unique_ptr<DirectoryWriter> writer = util::make_unique<DirectoryWriter>();
  if (!writer->Open(path)) {
    diag->Error(DiagMessage(path) << writer->GetError());
    return {};
  }
  return std::move(writer);
}

std::unique_ptr<IArchiveWriter> CreateZipFileArchiveWriter(IDiagnostics* diag,
                                                           const StringPiece& path) {
  std::unique_ptr<ZipFileWriter> writer = util::make_unique<ZipFileWriter>();
  if (!writer->Open(path)) {
    diag->Error(DiagMessage(path) << writer->GetError());
    return {};
  }
  return std::move(writer);
}

}  // namespace aapt
