/*
 * Copyright (C) 2011 The Android Open Source Project
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

#include "core/native_frame.h"

namespace android {
namespace filterfw {

NativeFrame::NativeFrame(int size) : data_(NULL), size_(size), capacity_(size) {
  data_ = capacity_ == 0 ? NULL : new uint8_t[capacity_];
}

NativeFrame::~NativeFrame() {
  delete[] data_;
}

bool NativeFrame::WriteData(const uint8_t* data, int offset, int size) {
  if (size_ >= (offset + size)) {
    memcpy(data_ + offset, data, size);
    return true;
  }
  return false;
}

bool NativeFrame::SetData(uint8_t* data, int size) {
  delete[] data_;
  size_ = capacity_ = size;
  data_ = data;
  return true;
}

NativeFrame* NativeFrame::Clone() const {
  NativeFrame* result = new NativeFrame(size_);
  if (data_)
    result->WriteData(data_, 0, size_);
  return result;
}

bool NativeFrame::Resize(int newSize) {
  if (newSize <= capacity_ && newSize >= 0) {
    size_ = newSize;
    return true;
  }
  return false;
}

} // namespace filterfw
} // namespace android
