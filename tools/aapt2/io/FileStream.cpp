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

#include "io/FileStream.h"

#include <errno.h>   // for errno
#include <fcntl.h>   // for O_RDONLY
#include <unistd.h>  // for read

#include "android-base/errors.h"
#include "android-base/file.h"  // for O_BINARY
#include "android-base/macros.h"
#include "android-base/utf8.h"

using ::android::base::SystemErrorCodeToString;

namespace aapt {
namespace io {

FileInputStream::FileInputStream(const std::string& path, size_t buffer_capacity)
    : FileInputStream(::android::base::utf8::open(path.c_str(), O_RDONLY | O_BINARY),
                      buffer_capacity) {
}

FileInputStream::FileInputStream(int fd, size_t buffer_capacity)
    : fd_(fd),
      buffer_capacity_(buffer_capacity),
      buffer_offset_(0u),
      buffer_size_(0u),
      total_byte_count_(0u) {
  if (fd_ == -1) {
    error_ = SystemErrorCodeToString(errno);
  } else {
    buffer_.reset(new uint8_t[buffer_capacity_]);
  }
}

bool FileInputStream::Next(const void** data, size_t* size) {
  if (HadError()) {
    return false;
  }

  // Deal with any remaining bytes after BackUp was called.
  if (buffer_offset_ != buffer_size_) {
    *data = buffer_.get() + buffer_offset_;
    *size = buffer_size_ - buffer_offset_;
    total_byte_count_ += buffer_size_ - buffer_offset_;
    buffer_offset_ = buffer_size_;
    return true;
  }

  ssize_t n = TEMP_FAILURE_RETRY(read(fd_, buffer_.get(), buffer_capacity_));
  if (n < 0) {
    error_ = SystemErrorCodeToString(errno);
    fd_.reset();
    buffer_.reset();
    return false;
  }

  buffer_size_ = static_cast<size_t>(n);
  buffer_offset_ = buffer_size_;
  total_byte_count_ += buffer_size_;

  *data = buffer_.get();
  *size = buffer_size_;
  return buffer_size_ != 0u;
}

void FileInputStream::BackUp(size_t count) {
  if (count > buffer_offset_) {
    count = buffer_offset_;
  }
  buffer_offset_ -= count;
  total_byte_count_ -= count;
}

size_t FileInputStream::ByteCount() const {
  return total_byte_count_;
}

bool FileInputStream::HadError() const {
  return fd_ == -1;
}

std::string FileInputStream::GetError() const {
  return error_;
}

FileOutputStream::FileOutputStream(const std::string& path, int mode, size_t buffer_capacity)
    : FileOutputStream(::android::base::utf8::open(path.c_str(), mode), buffer_capacity) {
}

FileOutputStream::FileOutputStream(int fd, size_t buffer_capacity)
    : fd_(fd), buffer_capacity_(buffer_capacity), buffer_offset_(0u), total_byte_count_(0u) {
  if (fd_ == -1) {
    error_ = SystemErrorCodeToString(errno);
  } else {
    buffer_.reset(new uint8_t[buffer_capacity_]);
  }
}

FileOutputStream::~FileOutputStream() {
  // Flush the buffer.
  Flush();
}

bool FileOutputStream::Next(void** data, size_t* size) {
  if (fd_ == -1 || HadError()) {
    return false;
  }

  if (buffer_offset_ == buffer_capacity_) {
    if (!FlushImpl()) {
      return false;
    }
  }

  const size_t buffer_size = buffer_capacity_ - buffer_offset_;
  *data = buffer_.get() + buffer_offset_;
  *size = buffer_size;
  total_byte_count_ += buffer_size;
  buffer_offset_ = buffer_capacity_;
  return true;
}

void FileOutputStream::BackUp(size_t count) {
  if (count > buffer_offset_) {
    count = buffer_offset_;
  }
  buffer_offset_ -= count;
  total_byte_count_ -= count;
}

size_t FileOutputStream::ByteCount() const {
  return total_byte_count_;
}

bool FileOutputStream::Flush() {
  if (!HadError()) {
    return FlushImpl();
  }
  return false;
}

bool FileOutputStream::FlushImpl() {
  ssize_t n = TEMP_FAILURE_RETRY(write(fd_, buffer_.get(), buffer_offset_));
  if (n < 0) {
    error_ = SystemErrorCodeToString(errno);
    fd_.reset();
    buffer_.reset();
    return false;
  }

  buffer_offset_ = 0u;
  return true;
}

bool FileOutputStream::HadError() const {
  return fd_ == -1;
}

std::string FileOutputStream::GetError() const {
  return error_;
}

}  // namespace io
}  // namespace aapt
