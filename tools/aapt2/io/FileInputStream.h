/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef AAPT_IO_FILEINPUTSTREAM_H
#define AAPT_IO_FILEINPUTSTREAM_H

#include "io/Io.h"

#include <memory>
#include <string>

#include "android-base/macros.h"
#include "android-base/unique_fd.h"

namespace aapt {
namespace io {

class FileInputStream : public InputStream {
 public:
  explicit FileInputStream(const std::string& path, size_t buffer_capacity = 4096);

  // Takes ownership of `fd`.
  explicit FileInputStream(int fd, size_t buffer_capacity = 4096);

  bool Next(const void** data, size_t* size) override;

  void BackUp(size_t count) override;

  size_t ByteCount() const override;

  bool HadError() const override;

  std::string GetError() const override;

 private:
  DISALLOW_COPY_AND_ASSIGN(FileInputStream);

  android::base::unique_fd fd_;
  std::string error_;
  std::unique_ptr<uint8_t[]> buffer_;
  size_t buffer_capacity_;
  size_t buffer_offset_;
  size_t buffer_size_;
  size_t total_byte_count_;
};

}  // namespace io
}  // namespace aapt

#endif  // AAPT_IO_FILEINPUTSTREAM_H
