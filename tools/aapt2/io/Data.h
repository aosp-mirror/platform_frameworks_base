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

#ifndef AAPT_IO_DATA_H
#define AAPT_IO_DATA_H

#include <memory>

#include "android-base/macros.h"
#include "androidfw/Streams.h"
#include "utils/FileMap.h"

namespace aapt {
namespace io {

// Interface for a block of contiguous memory. An instance of this interface owns the data.
class IData : public android::KnownSizeInputStream {
 public:
  virtual ~IData() = default;

  virtual const void* data() const = 0;
  virtual size_t size() const = 0;

  virtual size_t TotalSize() const override {
    return size();
  }
};

class DataSegment : public IData {
 public:
  explicit DataSegment(std::unique_ptr<IData> data, size_t offset, size_t len)
      : data_(std::move(data)), offset_(offset), len_(len), next_read_(offset) {}
  virtual ~DataSegment() = default;

  const void* data() const override {
    return static_cast<const uint8_t*>(data_->data()) + offset_;
  }

  size_t size() const override { return len_; }

  bool Next(const void** data, size_t* size) override {
    if (next_read_ == offset_ + len_) {
      return false;
    }
    *data = static_cast<const uint8_t*>(data_->data()) + next_read_;
    *size = len_ - (next_read_ - offset_);
    next_read_ = offset_ + len_;
    return true;
  }

  void BackUp(size_t count) override {
    if (count > next_read_ - offset_) {
      next_read_ = offset_;
    } else {
      next_read_ -= count;
    }
  }

  bool CanRewind() const override { return true; }

  bool Rewind() override {
    next_read_ = offset_;
    return true;
  }

  size_t ByteCount() const override { return next_read_ - offset_; }

  bool HadError() const override { return false; }

 private:
  DISALLOW_COPY_AND_ASSIGN(DataSegment);

  std::unique_ptr<IData> data_;
  size_t offset_;
  size_t len_;
  size_t next_read_;
};

// Implementation of IData that exposes a memory mapped file.
// The mmapped file is owned by this object.
class MmappedData : public IData {
 public:
  explicit MmappedData(android::FileMap&& map) : map_(std::forward<android::FileMap>(map)) {}
  virtual ~MmappedData() = default;

  const void* data() const override { return map_.getDataPtr(); }

  size_t size() const override { return map_.getDataLength(); }

  bool Next(const void** data, size_t* size) override {
    if (next_read_ == map_.getDataLength()) {
      return false;
    }
    *data = reinterpret_cast<const uint8_t*>(map_.getDataPtr()) + next_read_;
    *size = map_.getDataLength() - next_read_;
    next_read_ = map_.getDataLength();
    return true;
  }

  void BackUp(size_t count) override {
    if (count > next_read_) {
      next_read_ = 0;
    } else {
      next_read_ -= count;
    }
  }

  bool CanRewind() const override { return true; }

  bool Rewind() override {
    next_read_ = 0;
    return true;
  }

  size_t ByteCount() const override { return next_read_; }

  bool HadError() const override { return false; }

 private:
  DISALLOW_COPY_AND_ASSIGN(MmappedData);

  android::FileMap map_;
  size_t next_read_ = 0;
};

// Implementation of IData that exposes a block of memory that was malloc'ed (new'ed).
// The memory is owned by this object.
class MallocData : public IData {
 public:
  MallocData(std::unique_ptr<const uint8_t[]> data, size_t size)
      : data_(std::move(data)), size_(size) {}
  virtual ~MallocData() = default;

  const void* data() const override { return data_.get(); }

  size_t size() const override { return size_; }

  bool Next(const void** data, size_t* size) override {
    if (next_read_ == size_) {
      return false;
    }
    *data = data_.get() + next_read_;
    *size = size_ - next_read_;
    next_read_ = size_;
    return true;
  }

  void BackUp(size_t count) override {
    if (count > next_read_) {
      next_read_ = 0;
    } else {
      next_read_ -= count;
    }
  }

  bool CanRewind() const override { return true; }

  bool Rewind() override {
    next_read_ = 0;
    return true;
  }

  size_t ByteCount() const override { return next_read_; }

  bool HadError() const override { return false; }

 private:
  DISALLOW_COPY_AND_ASSIGN(MallocData);

  std::unique_ptr<const uint8_t[]> data_;
  size_t size_;
  size_t next_read_ = 0;
};

// When mmap fails because the file has length 0, we use the EmptyData to simulate data of length 0.
class EmptyData : public IData {
 public:
  virtual ~EmptyData() = default;

  const void* data() const override {
    static const uint8_t d = 0;
    return &d;
  }

  size_t size() const override { return 0u; }

  bool Next(const void** /*data*/, size_t* /*size*/) override { return false; }

  void BackUp(size_t /*count*/) override {}

  bool CanRewind() const override { return true; }

  bool Rewind() override { return true; }

  size_t ByteCount() const override { return 0u; }

  bool HadError() const override { return false; }
};

}  // namespace io
}  // namespace aapt

#endif /* AAPT_IO_DATA_H */
