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

#pragma once

#include "BigBuffer.h"
#include "Streams.h"

namespace android {

class BigBufferInputStream : public KnownSizeInputStream {
 public:
  inline explicit BigBufferInputStream(const BigBuffer* buffer)
      : owning_buffer_(0), buffer_(buffer), iter_(buffer->begin()) {
  }

  inline explicit BigBufferInputStream(android::BigBuffer&& buffer)
      : owning_buffer_(std::move(buffer)), buffer_(&owning_buffer_), iter_(buffer_->begin()) {
  }

  virtual ~BigBufferInputStream() = default;

  bool Next(const void** data, size_t* size) override;

  void BackUp(size_t count) override;

  bool CanRewind() const override;

  bool Rewind() override;

  size_t ByteCount() const override;

  bool HadError() const override;

  size_t TotalSize() const override;

  bool ReadFullyAtOffset(void* data, size_t byte_count, off64_t offset) override;

 private:
  DISALLOW_COPY_AND_ASSIGN(BigBufferInputStream);

  android::BigBuffer owning_buffer_;
  const BigBuffer* buffer_;
  BigBuffer::const_iterator iter_;
  size_t offset_ = 0;
  size_t bytes_read_ = 0;
};

class BigBufferOutputStream : public OutputStream {
 public:
  inline explicit BigBufferOutputStream(BigBuffer* buffer) : buffer_(buffer) {
  }
  virtual ~BigBufferOutputStream() = default;

  bool Next(void** data, size_t* size) override;

  void BackUp(size_t count) override;

  size_t ByteCount() const override;

  bool HadError() const override;

 private:
  DISALLOW_COPY_AND_ASSIGN(BigBufferOutputStream);

  BigBuffer* buffer_;
};

}  // namespace android