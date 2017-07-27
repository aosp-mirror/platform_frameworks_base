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

#include "text/Utf8Iterator.h"

#include "android-base/logging.h"
#include "utils/Unicode.h"

using ::android::StringPiece;

namespace aapt {
namespace text {

Utf8Iterator::Utf8Iterator(const StringPiece& str)
    : str_(str), current_pos_(0), next_pos_(0), current_codepoint_(0) {
  DoNext();
}

void Utf8Iterator::DoNext() {
  current_pos_ = next_pos_;
  int32_t result = utf32_from_utf8_at(str_.data(), str_.size(), current_pos_, &next_pos_);
  if (result == -1) {
    current_codepoint_ = 0u;
  } else {
    current_codepoint_ = static_cast<char32_t>(result);
  }
}

bool Utf8Iterator::HasNext() const {
  return current_codepoint_ != 0;
}

size_t Utf8Iterator::Position() const {
  return current_pos_;
}

void Utf8Iterator::Skip(int amount) {
  while (amount > 0 && HasNext()) {
    Next();
    --amount;
  }
}

char32_t Utf8Iterator::Next() {
  CHECK(HasNext()) << "Next() called after iterator exhausted";
  char32_t result = current_codepoint_;
  DoNext();
  return result;
}

}  // namespace text
}  // namespace aapt
