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

#include "io/StringStream.h"

using ::android::StringPiece;

namespace aapt {
namespace io {

StringInputStream::StringInputStream(StringPiece str) : str_(str), offset_(0u) {
}

bool StringInputStream::Next(const void** data, size_t* size) {
  if (offset_ == str_.size()) {
    return false;
  }

  *data = str_.data() + offset_;
  *size = str_.size() - offset_;
  offset_ = str_.size();
  return true;
}

void StringInputStream::BackUp(size_t count) {
  if (count > offset_) {
    offset_ = 0u;
  } else {
    offset_ -= count;
  }
}

size_t StringInputStream::ByteCount() const {
  return offset_;
}

size_t StringInputStream::TotalSize() const {
  return str_.size();
}

bool StringInputStream::ReadFullyAtOffset(void* data, size_t byte_count, off64_t offset) {
  if (byte_count == 0) {
    return true;
  }
  if (offset < 0) {
    return false;
  }
  if (offset > std::numeric_limits<off64_t>::max() - byte_count) {
    return false;
  }
  if (offset + byte_count > str_.size()) {
    return false;
  }
  memcpy(data, str_.data() + offset, byte_count);
  return true;
}

StringOutputStream::StringOutputStream(std::string* str, size_t buffer_capacity)
    : str_(str),
      buffer_capacity_(buffer_capacity),
      buffer_offset_(0u),
      buffer_(new char[buffer_capacity]) {
}

StringOutputStream::~StringOutputStream() {
  Flush();
}

bool StringOutputStream::Next(void** data, size_t* size) {
  if (buffer_offset_ == buffer_capacity_) {
    FlushImpl();
  }

  *data = buffer_.get() + buffer_offset_;
  *size = buffer_capacity_ - buffer_offset_;
  buffer_offset_ = buffer_capacity_;
  return true;
}

void StringOutputStream::BackUp(size_t count) {
  if (count > buffer_offset_) {
    buffer_offset_ = 0u;
  } else {
    buffer_offset_ -= count;
  }
}

size_t StringOutputStream::ByteCount() const {
  return str_->size() + buffer_offset_;
}

void StringOutputStream::Flush() {
  if (buffer_offset_ != 0u) {
    FlushImpl();
  }
}

void StringOutputStream::FlushImpl() {
  str_->append(buffer_.get(), buffer_offset_);
  buffer_offset_ = 0u;
}

}  // namespace io
}  // namespace aapt
