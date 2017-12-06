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

#ifndef AAPT_TEXT_UTF8ITERATOR_H
#define AAPT_TEXT_UTF8ITERATOR_H

#include "android-base/macros.h"
#include "androidfw/StringPiece.h"

namespace aapt {
namespace text {

class Utf8Iterator {
 public:
  explicit Utf8Iterator(const android::StringPiece& str);

  bool HasNext() const;

  // Returns the current position of the iterator in bytes of the source UTF8 string.
  // This position is the start of the codepoint returned by the next call to Next().
  size_t Position() const;

  void Skip(int amount);

  char32_t Next();

 private:
  DISALLOW_COPY_AND_ASSIGN(Utf8Iterator);

  void DoNext();

  android::StringPiece str_;
  size_t current_pos_;
  size_t next_pos_;
  char32_t current_codepoint_;
};

}  // namespace text
}  // namespace aapt

#endif  // AAPT_TEXT_UTF8ITERATOR_H
