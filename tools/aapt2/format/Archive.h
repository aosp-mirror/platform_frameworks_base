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

#ifndef AAPT_FORMAT_ARCHIVE_H
#define AAPT_FORMAT_ARCHIVE_H

#include <fstream>
#include <memory>
#include <string>
#include <vector>

#include "androidfw/BigBuffer.h"
#include "androidfw/IDiagnostics.h"
#include "androidfw/Streams.h"
#include "androidfw/StringPiece.h"
#include "google/protobuf/io/zero_copy_stream_impl_lite.h"
#include "util/Files.h"

namespace aapt {

struct ArchiveEntry {
  enum : uint32_t {
    kCompress = 0x01,
    kAlign = 0x02,
  };

  std::string path;
  uint32_t flags;
  size_t uncompressed_size;
};

class IArchiveWriter : public ::google::protobuf::io::CopyingOutputStream {
 public:
  virtual ~IArchiveWriter() = default;

  virtual bool WriteFile(android::StringPiece path, uint32_t flags, android::InputStream* in) = 0;

  // Starts a new entry and allows caller to write bytes to it sequentially.
  // Only use StartEntry if code you do not control needs to write to a CopyingOutputStream.
  // Prefer WriteFile instead of manually calling StartEntry/FinishEntry.
  virtual bool StartEntry(android::StringPiece path, uint32_t flags) = 0;

  // Called to finish writing an entry previously started by StartEntry.
  // Prefer WriteFile instead of manually calling StartEntry/FinishEntry.
  virtual bool FinishEntry() = 0;

  // CopyingOutputStream implementation that allows sequential writes to this archive. Only
  // valid between calls to StartEntry and FinishEntry.
  virtual bool Write(const void* buffer, int size) = 0;

  // Returns true if there was an error writing to the archive.
  // The resulting error message can be retrieved from GetError().
  virtual bool HadError() const = 0;

  // Returns the error message if HadError() returns true.
  virtual std::string GetError() const = 0;
};

std::unique_ptr<IArchiveWriter> CreateDirectoryArchiveWriter(android::IDiagnostics* diag,
                                                             android::StringPiece path);

std::unique_ptr<IArchiveWriter> CreateZipFileArchiveWriter(android::IDiagnostics* diag,
                                                           android::StringPiece path);

}  // namespace aapt

#endif /* AAPT_FORMAT_ARCHIVE_H */
