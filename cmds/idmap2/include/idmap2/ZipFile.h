/*
 * Copyright (C) 2018 The Android Open Source Project
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

#ifndef IDMAP2_INCLUDE_IDMAP2_ZIPFILE_H_
#define IDMAP2_INCLUDE_IDMAP2_ZIPFILE_H_

#include <memory>
#include <string>

#include "android-base/macros.h"
#include "idmap2/Result.h"
#include "ziparchive/zip_archive.h"

namespace android::idmap2 {

struct MemoryChunk {
  size_t size;
  uint8_t buf[0];

  static std::unique_ptr<MemoryChunk> Allocate(size_t size);

 private:
  MemoryChunk() {
  }
};

class ZipFile {
 public:
  static std::unique_ptr<const ZipFile> Open(const std::string& path);

  std::unique_ptr<const MemoryChunk> Uncompress(const std::string& entryPath) const;
  Result<uint32_t> Crc(const std::string& entryPath) const;

  ~ZipFile();

 private:
  explicit ZipFile(const ::ZipArchiveHandle handle) : handle_(handle) {
  }

  const ::ZipArchiveHandle handle_;

  DISALLOW_COPY_AND_ASSIGN(ZipFile);
};

}  // namespace android::idmap2

#endif  // IDMAP2_INCLUDE_IDMAP2_ZIPFILE_H_
