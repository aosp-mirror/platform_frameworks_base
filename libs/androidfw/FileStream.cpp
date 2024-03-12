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

#include "androidfw/FileStream.h"

#include <errno.h>   // for errno
#include <fcntl.h>   // for O_RDONLY
#include <unistd.h>  // for read

#include "android-base/errors.h"
#include "android-base/file.h"  // for O_BINARY
#include "android-base/logging.h"
#include "android-base/macros.h"
#include "android-base/utf8.h"

#if defined(_WIN32)
// This is only needed for O_CLOEXEC.
#include <windows.h>
#define O_CLOEXEC O_NOINHERIT
#endif

using ::android::base::SystemErrorCodeToString;
using ::android::base::unique_fd;

namespace android {

FileInputStream::FileInputStream(const std::string& path, size_t buffer_capacity)
    : should_close_(true), buffer_capacity_(buffer_capacity) {
  int mode = O_RDONLY | O_CLOEXEC | O_BINARY;
  fd_ = TEMP_FAILURE_RETRY(::android::base::utf8::open(path.c_str(), mode));
  if (fd_ == -1) {
    error_ = SystemErrorCodeToString(errno);
  } else {
    buffer_.reset(new uint8_t[buffer_capacity_]);
  }
}

FileInputStream::FileInputStream(int fd, size_t buffer_capacity)
    : fd_(fd), should_close_(true), buffer_capacity_(buffer_capacity) {
  if (fd_ < 0) {
    error_ = "Bad File Descriptor";
  } else {
    buffer_.reset(new uint8_t[buffer_capacity_]);
  }
}

FileInputStream::FileInputStream(android::base::borrowed_fd fd, size_t buffer_capacity)
    : fd_(fd.get()), should_close_(false), buffer_capacity_(buffer_capacity) {

  if (fd_ < 0) {
    error_ = "Bad File Descriptor";
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
    if (fd_ != -1) {
      if (should_close_) {
        close(fd_);
      }
      fd_ = -1;
    }
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

bool FileInputStream::ReadFullyAtOffset(void* data, size_t byte_count, off64_t offset) {
  return base::ReadFullyAtOffset(fd_, data, byte_count, offset);
}

FileOutputStream::FileOutputStream(const std::string& path, size_t buffer_capacity)
    : buffer_capacity_(buffer_capacity) {
  int mode = O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC | O_BINARY;
  owned_fd_.reset(TEMP_FAILURE_RETRY(::android::base::utf8::open(path.c_str(), mode, 0666)));
  fd_ = owned_fd_.get();
  if (fd_ < 0) {
    error_ = SystemErrorCodeToString(errno);
  } else {
    buffer_.reset(new uint8_t[buffer_capacity_]);
  }
}

FileOutputStream::FileOutputStream(unique_fd fd, size_t buffer_capacity)
    : FileOutputStream(fd.get(), buffer_capacity) {
  owned_fd_ = std::move(fd);
}

FileOutputStream::FileOutputStream(int fd, size_t buffer_capacity)
    : fd_(fd), buffer_capacity_(buffer_capacity) {
  if (fd_ < 0) {
    error_ = "Bad File Descriptor";
  } else {
    buffer_.reset(new uint8_t[buffer_capacity_]);
  }
}

FileOutputStream::~FileOutputStream() {
  // Flush the buffer.
  Flush();
}

bool FileOutputStream::Next(void** data, size_t* size) {
  if (HadError()) {
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
    owned_fd_.reset();
    fd_ = -1;
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

}  // namespace android
