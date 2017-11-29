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

#include "io/StringInputStream.h"

using ::android::StringPiece;

namespace aapt {
namespace io {

StringInputStream::StringInputStream(const StringPiece& str) : str_(str), offset_(0u) {
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
    count = offset_;
  }
  offset_ -= count;
}

size_t StringInputStream::ByteCount() const {
  return offset_;
}

}  // namespace io
}  // namespace aapt
